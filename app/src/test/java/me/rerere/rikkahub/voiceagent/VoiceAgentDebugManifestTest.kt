package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class VoiceAgentDebugManifestTest {
    @Test
    fun `debug receivers require shell held permission`() {
        val audioReceiver = findReceiver(".voiceagent.debug.VoiceAudioDebugInjectionReceiver")
        val seedReceiver = findReceiver(".voiceagent.debug.VoiceAgentDebugSeedReceiver")

        assertEquals("android.permission.DUMP", audioReceiver.getAttribute("android:permission"))
        assertEquals("android.permission.DUMP", seedReceiver.getAttribute("android:permission"))
    }

    @Test
    fun `debug receivers remain exported for adb workflows`() {
        val audioReceiver = findReceiver(".voiceagent.debug.VoiceAudioDebugInjectionReceiver")
        val seedReceiver = findReceiver(".voiceagent.debug.VoiceAgentDebugSeedReceiver")

        assertEquals("true", audioReceiver.getAttribute("android:exported"))
        assertEquals("true", seedReceiver.getAttribute("android:exported"))
    }

    @Test
    fun `debug receivers keep expected actions`() {
        val audioReceiver = findReceiver(".voiceagent.debug.VoiceAudioDebugInjectionReceiver")
        val seedReceiver = findReceiver(".voiceagent.debug.VoiceAgentDebugSeedReceiver")

        assertEquals(
            listOf("me.rerere.rikkahub.debug.voiceagent.INJECT_PCM"),
            audioReceiver.actionNames(),
        )
        assertEquals(
            listOf("me.rerere.rikkahub.debug.voiceagent.SEED_HERMES_PROVIDER"),
            seedReceiver.actionNames(),
        )
    }

    private fun findReceiver(name: String): Element {
        val manifest = File("src/debug/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val receivers = document.getElementsByTagName("receiver")

        return (0 until receivers.length)
            .map { receivers.item(it) as Element }
            .first { it.getAttribute("android:name") == name }
    }

    private fun Element.actionNames(): List<String> {
        val actions = getElementsByTagName("action")

        return (0 until actions.length)
            .map { actions.item(it) as Element }
            .map { it.getAttribute("android:name") }
    }
}
