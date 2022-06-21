// WITH_STDLIB
// IGNORE_BACKEND: JVM
// WORKS_WHEN_VALUE_CLASS
// LANGUAGE: +ValueClasses, +GenericInlineClassParameter

OPTIONAL_JVM_INLINE_ANNOTATION
value class Z<T: Int>(val int: T)

OPTIONAL_JVM_INLINE_ANNOTATION
value class L<T: Long>(val long: T)

OPTIONAL_JVM_INLINE_ANNOTATION
value class Str<T: String>(val string: T)

OPTIONAL_JVM_INLINE_ANNOTATION
value class Obj<T: Any>(val obj: T)

fun box(): String {
    var xz = Z(0)
    var xl = L(0L)
    var xs = Str("")
    var xo = Obj("")

    run {
        xz = Z(42)
        xl = L(1234L)
        xs = Str("abc")
        xo = Obj("def")
    }

    if (xz.int != 42) throw AssertionError()
    if (xl.long != 1234L) throw AssertionError()
    if (xs.string != "abc") throw AssertionError()
    if (xo.obj != "def") throw AssertionError()

    return "OK"
}