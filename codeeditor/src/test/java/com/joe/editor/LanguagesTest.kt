package com.joe.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {
    @Test
    fun detectsAdditionalLanguagesByExtension() {
        assertEquals(Languages.Toml, Languages.forFileName("Cargo.toml"))
        assertEquals(Languages.R, Languages.forFileName("analysis.RData"))
        assertEquals(Languages.PowerShell, Languages.forFileName("build.ps1"))
        assertEquals(Languages.Nix, Languages.forFileName("flake.nix"))
    }

    @Test
    fun detectsDockerfilesAndMakefiles() {
        assertEquals(Languages.Shell, Languages.forFileName("Dockerfile"))
        assertEquals(Languages.Shell, Languages.forFileName("Makefile"))
    }

    @Test
    fun additionalLanguagesHighlightKeywordsAndComments() {
        val result = Languages.Haskell.scan("-- comment\nmodule Demo where")
        assertTrue(result.tokens.any { it.type == TokenType.Comment })
        assertTrue(result.tokens.any { it.type == TokenType.Keyword && it.start == 11 })
    }
}
