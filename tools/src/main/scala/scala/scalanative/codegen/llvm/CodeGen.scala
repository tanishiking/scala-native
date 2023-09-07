package scala.scalanative.codegen
package llvm

import java.io.File
import java.nio.file.{Path, Paths, Files}
import scala.collection.mutable
import scala.scalanative.build.Config
import scala.scalanative.build.ScalaNative.{dumpDefns, encodedMainClass}
import scala.scalanative.io.VirtualDirectory
import scala.scalanative.nir._
import scala.scalanative.build
import scala.scalanative.linker.ReachabilityAnalysis
import scala.scalanative.util.{Scope, partitionBy, procs}
import java.nio.file.StandardCopyOption

import scala.scalanative.build.ScalaNative
import scala.scalanative.codegen.{Metadata => CodeGenMetadata}
import scala.concurrent._
import scala.util.Success

object CodeGen {

  /** Lower and generate code for given assembly. */
  def apply(config: build.Config, analysis: ReachabilityAnalysis.Result)(
      implicit ec: ExecutionContext
  ): Future[Seq[Path]] = {
    val defns = analysis.defns
    val proxies = GenerateReflectiveProxies(analysis.dynimpls, defns)

    implicit def logger: build.Logger = config.logger
    implicit val platform: PlatformInfo = PlatformInfo(config)
    implicit val meta: CodeGenMetadata =
      new CodeGenMetadata(analysis, config.compilerConfig, proxies)

    val generated = Generate(encodedMainClass(config), defns ++ proxies)
    val embedded = ResourceEmbedder(config)
    val lowered = lower(generated ++ embedded)
    lowered
      .andThen { case Success(defns) => dumpDefns(config, "lowered", defns) }
      .flatMap(emit(config, _))
  }

  private[scalanative] def lower(
      defns: Seq[Defn]
  )(implicit
      meta: CodeGenMetadata,
      logger: build.Logger,
      ec: ExecutionContext
  ): Future[Seq[Defn]] = {

    val loweringJobs = partitionBy(defns)(_.name).map {
      case (_, defns) => Future(Lower(defns))
    }

    Future
      .foldLeft(loweringJobs)(mutable.UnrolledBuffer.empty[Defn]) {
        case (buffer, defns) => buffer ++= defns
      }
      .map(_.toSeq)
  }

  /** Generate code for given assembly. */
  private def emit(config: build.Config, assembly: Seq[Defn])(implicit
      meta: CodeGenMetadata,
      ec: ExecutionContext
  ): Future[Seq[Path]] =
    Scope { implicit in =>
      val env = assembly.map(defn => defn.name -> defn).toMap
      val workDir = VirtualDirectory.real(config.workDir)

      /** C:a\b\c.txt -> a\b\c.txt p:\a\b\c.txt -> a\b\c.txt /a/b/c.txt ->
       *  a/b/c.txt
       */
      def dropPrefix(fileName: String): String = {
        val driveLetter = "^[A-Za-z]:".r
        val supportDriveLetter =
          build.Platform.isWindows || build.Platform.isCygwin || build.Platform.isMsys
        val hasDriveLetter =
          supportDriveLetter && driveLetter.findFirstIn(fileName).isDefined

        val noDriveLetter = if (hasDriveLetter) fileName.drop(2) else fileName
        if (noDriveLetter.startsWith(File.separator))
          noDriveLetter.drop(File.separator.length())
        else noDriveLetter
      }

      def dropSuffix(fileName: String): String =
        if (fileName.endsWith(File.separator))
          fileName.dropRight(File.separator.length())
        else fileName

      def sourceDirOf(pos: Position): Position.SourceFile = {
        if (pos == null || pos.isEmpty) new Position.SourceFile("__empty")
        else
          Paths.get(pos.source.getPath()).getParent().toUri()
      }

      // Partition into multiple LLVM IR files proportional to number
      // of available processesors. This prevents LLVM from optimizing
      // across IR module boundary unless LTO is turned on.
      def separate(): Future[Seq[Path]] = {
        Future
          .traverse(
            partitionBy(assembly, procs)(x => sourceDirOf(x.pos)).toSeq
          ) {
            case (id, defns) =>
              Future {
                val sorted = defns.sortBy(_.name)
                Impl(env, sorted).gen(id.toString, workDir)
              }
          }
      }

      // Incremental compilation code generation
      def seperateIncrementally(): Future[Seq[Path]] = {
        val ctx = new IncrementalCodeGenContext(config.workDir)
        ctx.collectFromPreviousState()

        // Partition into multiple LLVM IR files per Scala source file originated from.
        // We previously partitioned LLVM IR files by package.
        // However, this caused issues with the Darwin linker when generating N_OSO symbols,
        // if a single Scala source file generates multiple LLVM IR files with the compilation unit DIEs
        // referencing the same Scala source file.
        // Because, the Darwin linker distinguishes compilation unit DIEs (debugging information entries)
        // by their DW_AT_name, DW_comp_dir attribute, and the object files' timestamps.
        // If the CU DIEs and timestamps are duplicated, the Darwin linker cannot distinguish the DIEs,
        // and one of the duplicates will be ignored.
        // As a result, the N_OSO symbol (which points to the object file path) is missing in the final binary,
        // and dsymutil fails to link some debug symbols from object files.
        // see: https://github.com/scala-native/scala-native/issues/3458#issuecomment-1701036738
        //
        // To address this issue, we partition into multiple LLVM IR files per Scala source file originated from.
        // This will ensure that each LLVM IR file only references a single Scala source file,
        // which will prevent the Darwin linker failing to generate N_OSO symbols.
        Future
          .traverse(assembly.groupBy(x => sourceDirOf(x.pos)).toSeq) {
            case (dir, defns) =>
              Future {
                val path = dropSuffix(dropPrefix(dir.getPath()))
                val outFile = config.workDir.resolve(s"$path.ll")
                val ownerDirectory = outFile.getParent()

                ctx.addEntry(path, defns)
                if (ctx.shouldCompile(path)) {
                  val sorted = defns.sortBy(_.name)
                  if (!Files.exists(ownerDirectory))
                    Files.createDirectories(ownerDirectory)
                  Impl(env, sorted).gen(path, workDir)
                } else {
                  assert(ownerDirectory.toFile.exists())
                  config.logger.debug(
                    s"Content of package has not changed, skiping generation of $path.ll"
                  )
                  config.workDir.resolve(s"$path.ll")
                }
              }
          }
          .andThen {
            case _ =>
              // Save current state for next compilation run
              ctx.dump()
              ctx.clear()
          }
      }

      // Generate a single LLVM IR file for the whole application.
      // This is an adhoc form of LTO. We use it in release mode if
      // Clang's LTO is not available.
      def single(): Future[Seq[Path]] = Future {
        val sorted = assembly.sortBy(_.name)
        Impl(env, sorted).gen(id = "out", workDir) :: Nil
      }

      import build.Mode._
      (config.mode, config.LTO) match {
        case (ReleaseFast | ReleaseSize | ReleaseFull, build.LTO.None) =>
          single()
        case _ =>
          if (config.compilerConfig.useIncrementalCompilation)
            seperateIncrementally()
          else separate()
      }
    }

  object Impl {
    import scala.scalanative.codegen.llvm.AbstractCodeGen
    import scala.scalanative.codegen.llvm.compat.os._

    def apply(env: Map[Global, Defn], defns: Seq[Defn])(implicit
        meta: CodeGenMetadata
    ): AbstractCodeGen = {
      new AbstractCodeGen(env, defns) {
        override val os: OsCompat = {
          if (this.meta.platform.targetsWindows) new WindowsCompat(this)
          else new UnixCompat(this)
        }
      }
    }
  }

  def depends(implicit platform: PlatformInfo): Seq[Global] = {
    val buf = mutable.UnrolledBuffer.empty[Global]
    buf ++= Lower.depends
    buf ++= Generate.depends
    buf += Rt.Object.name member Rt.ScalaEqualsSig
    buf += Rt.Object.name member Rt.ScalaHashCodeSig
    buf += Rt.Object.name member Rt.JavaEqualsSig
    buf += Rt.Object.name member Rt.JavaHashCodeSig
    buf.toSeq
  }
}
