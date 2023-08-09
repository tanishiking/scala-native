object Test {
  def main(args: Array[String]): Unit = f()

  def f() = g()

  def g() = error()

  def error() = new Error("test")
}
