package dev.lenshift.app.ai

import dev.lenshift.app.conversation.ChatHistoryFormat
import dev.lenshift.app.ui.markdown.MarkdownParser
import dev.lenshift.app.ui.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Code a model ran on the user's photo, leaked into its answer by a relay; from real answers. */
class LeakedToolCodeTest {

    private val leaked = """
        |from PIL import Image
        |img=Image.open('/mnt/data/image_1.jpg')
        |print(img.size)
        |display(img.crop((120,330,1430,600)).resize((1965,405)))
        |display(img.crop((200,170,780,330)).resize((1740,480)))
        |Not quite — there are a few mismatches between your original equation and your Laplace transform.
        |
        |Your original equation appears to be:
        |
        |${'$'}${'$'}y' + 2y = 16t${'$'}${'$'}
        """.trimMargin()

    @Test
    fun `the code the model ran is hidden and the answer starts with its first sentence`() {
        val shown = LeakedToolCode.strip(leaked)
        assertTrue(shown, shown.startsWith("Not quite — there are a few mismatches"))
        assertTrue(shown.endsWith("\$\$y' + 2y = 16t\$\$"))
        val first = MarkdownParser.parse(leaked).first() as MdBlock.Paragraph
        assertTrue(first.text, first.text.startsWith("Not quite"))
    }

    @Test
    fun `a single crop is hidden too`() {
        val answer = "from PIL import Image\nimg=Image.open('/mnt/data/image_1.jpg')\nprint(img.size)\n" +
            "display(img.resize((1440,900)))\nYou're very close!"
        assertEquals("You're very close!", LeakedToolCode.strip(answer))
    }

    @Test
    fun `code shown on purpose stays`() {
        val fenced = "Here is how:\n\n```python\nfrom PIL import Image\nimg = Image.open('/mnt/data/photo.png')\ndisplay(img)\n```"
        assertEquals(fenced, LeakedToolCode.strip(fenced))
        val prose = "Set y = 2x first.\nprint(y) shows it.\nThen solve."
        assertEquals(prose, LeakedToolCode.strip(prose))
    }

    @Test
    fun `leaked code later in an answer goes too`() {
        val answer = "Let me look closer.\n\nimg=Image.open('/mnt/data/image_1.jpg')\ndisplay(img.crop((1,2,3,4)))\nThe sign is negative."
        assertEquals("Let me look closer.\n\nThe sign is negative.", LeakedToolCode.strip(answer))
    }

    @Test
    fun `while it streams in, the first line is already hidden`() {
        assertEquals("", LeakedToolCode.strip("from PIL import Image"))
        assertEquals("", LeakedToolCode.strip("from PIL import Image\nimg=Image.open('/mnt/data/image_1.jpg')\n"))
    }

    @Test
    fun `a chat titled from its answer skips the code`() {
        assertEquals(
            "Not quite — there are a few mismatches between your original…",
            ChatHistoryFormat.title("", typed = false, firstAnswer = leaked)
        )
    }
}
