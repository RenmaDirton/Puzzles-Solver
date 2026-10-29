// JVM check for BoardCore.findButtons. Usage: java -cp coretest.jar ButtonTestKt shot.png ...
import com.puzzlesolver.BoardCore
import java.io.File
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    for (path in args) {
        val img = ImageIO.read(File(path))
        val w = img.width
        val h = img.height
        val pix = IntArray(w * h)
        img.getRGB(0, 0, w, h, pix, 0, w)
        val bs = BoardCore.findButtons(pix, w, h)
        println(File(path).name + ": " + bs.joinToString { "y=%.3f contrast=%d".format(it.cy.toFloat() / h, it.contrast) } + " cornerGlyph=" + BoardCore.cornerGlyph(pix, w, h))
    }
}
