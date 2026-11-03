package dev.lenshift.app.selection

/**
 * A framework-free integer rectangle.
 *
 * `android.graphics.Rect` is a stub in local JVM unit tests (every method returns 0), so
 * all selection maths is expressed in terms of this type and converted to a real [Rect]
 * only at the Android boundary. That keeps the crop logic — the part most likely to be
 * wrong and hardest to debug on a device — fully unit-testable.
 */
data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun toAndroidRect(): android.graphics.Rect = android.graphics.Rect(left, top, right, bottom)

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)

        fun fromAndroid(rect: android.graphics.Rect): IntRect =
            IntRect(rect.left, rect.top, rect.right, rect.bottom)
    }
}
