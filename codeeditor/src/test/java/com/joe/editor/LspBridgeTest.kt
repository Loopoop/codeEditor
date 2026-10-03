package com.joe.editor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LspBridgeTest {

    @Test
    fun testStandardLspClientSendsInitialization() = runBlocking {
        val input = ByteArrayInputStream(ByteArray(0))
        val output = ByteArrayOutputStream()

        val lspClient = StandardLspClient(input, output)
        lspClient.initialize("file:///workspace")

        val sent = output.toString(Charsets.UTF_8.name())
        assertNotNull(sent)
        org.junit.Assert.assertTrue("Should contain content length header", sent.contains("Content-Length:"))
        org.junit.Assert.assertTrue("Should contain initialize method", sent.contains("\"method\":\"initialize\""))
    }
}
