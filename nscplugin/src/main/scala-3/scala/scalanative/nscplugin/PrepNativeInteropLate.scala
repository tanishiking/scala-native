package scala.scalanative.nscplugin

import dotty.tools.dotc.plugins.PluginPhase
import dotty.tools._
import dotc._
import dotc.ast.tpd._
import dotc.transform.SymUtils.setter
import core.Contexts._
import core.Definitions
import core.Names._
import core.Symbols._
import core.Types._
import core.StdNames._
import core.Constants.Constant
import NirGenUtil.ContextCached
import dotty.tools.dotc.core.Flags

/** This phase does:
 *    - handle TypeApply -> Apply conversion for intrinsic methods
 */
object PostInlineNativeInterop {
  val name = "scalanative-prepareInterop-postinline"
}

class PostInlineNativeInterop extends PluginPhase {
  override val runsAfter = Set(transform.Inlining.name, PrepNativeInterop.name)
  override val runsBefore = Set(transform.FirstTransform.name)
  val phaseName = PostInlineNativeInterop.name
  override def description: String = "prepare ASTs for Native interop"

  def defn(using Context): Definitions = ctx.definitions
  def defnNir(using Context): NirDefinitions = NirDefinitions.get

  private def isTopLevelExtern(dd: ValOrDefDef)(using Context) = {
    dd.rhs.symbol == defnNir.UnsafePackage_extern &&
    dd.symbol.isWrappedToplevelDef
  }

  private class DealiasTypeMapper(using Context) extends TypeMap {
    override def apply(tp: Type): Type =
      val sym = tp.typeSymbol
      val dealiased =
        if sym.isOpaqueAlias then sym.opaqueAlias
        else tp
      dealiased.widenDealias match
        case AppliedType(tycon, args) =>
          AppliedType(this(tycon), args.map(this))
        case ty => ty
  }

  override def transformApply(tree: Apply)(using Context): Tree = {
    val Apply(fun, evidences) = tree
    val defnNir = this.defnNir
    def dealiasTypeMapper = DealiasTypeMapper()

    fun match
      // fromScalaFunction[T1, R](fn) -> fromScalaFunction(classOf[T1], cassOf[R])(fn)
      case Apply(TypeApply(tfun, tArgs), args) if defnNir.CFuncPtr_fromScalaFunction.find(_ == tfun.symbol).isDefined =>
        val idx = defnNir.CFuncPtr_fromScalaFunction.indexOf(tfun.symbol)
        // val defnNir.CFuncPtr_fromScalaFunction.find(_ == s).get
        val transformed = defnNir._CFuncPtr_fromScalaFunction(idx)
        val cls = defnNir.CFuncPtrNClass(idx)
        // tArgs.map(a => Literal(Constant(dealiasTypeMapper(a.tpe))))
        val tys = tArgs.map(t => dealiasTypeMapper(t.tpe))
        val res = cpy
          .Apply(tree)(
            ref(transformed),
            args ++ (tArgs ++ evidences).map(t => Literal(Constant(t.tpe)))
          )
          .withAttachment(NirDefinitions.NonErasedTypes, tys.toArray)
          .withAttachment(NirDefinitions.NonErasedType, cls.typeRef)
        res

      case _ => tree

  }



  override def transformTypeApply(tree: TypeApply)(using Context): Tree = {
    val TypeApply(fun, tArgs) = tree
    val defnNir = this.defnNir
    def dealiasTypeMapper = DealiasTypeMapper()

    // sizeOf[T] -> sizeOf(classOf[T])
    fun.symbol match
      case defnNir.Intrinsics_sizeOfType =>
        val tpe = dealiasTypeMapper(tArgs.head.tpe)
        cpy
          .Apply(tree)(
            ref(defnNir.Intrinsics_sizeOf),
            List(Literal(Constant(tpe)))
          )
          .withAttachment(NirDefinitions.NonErasedType, tpe)

      // alignmentOf[T] -> alignmentOf(classOf[T])
      case defnNir.Intrinsics_alignmentOfType =>
        val tpe = dealiasTypeMapper(tArgs.head.tpe)
        cpy
          .Apply(tree)(
            ref(defnNir.Intrinsics_alignmentOf),
            List(Literal(Constant(tpe)))
          )
          .withAttachment(NirDefinitions.NonErasedType, tpe)

      case _ => tree
  }

}
