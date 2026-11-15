package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.utils.SettingsSchema.Actions
import com.fcaronte.aabrowser.utils.SettingsSchema.Choice
import com.fcaronte.aabrowser.utils.SettingsSchema.Group
import com.fcaronte.aabrowser.utils.SettingsSchema.Stepper
import com.fcaronte.aabrowser.utils.SettingsSchema.Switch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * La pagina delle impostazioni disegna quello che dice lo schema e scrive solo le chiavi di SettingsApi: i due devono
 * restare allineati (una riga senza chi la scrive non farebbe niente, una chiave senza riga non servirebbe a niente), e ogni
 * testo deve esistere in tutte le lingue.
 */
class SettingsSchemaTest {
    private val rows = SettingsSchema.allRows()

    @Test
    fun everyRowThatChangesSomethingHasAKeyTheAppWrites() {
        val missing = SettingsSchema.interactiveKeys() - SettingsApi.settingKeys
        assertEquals("righe senza chi le scrive: $missing", emptySet<String>(), missing)
    }

    @Test
    fun everyKeyTheAppWritesIsOnThePage() {
        val unused = SettingsApi.settingKeys - SettingsSchema.interactiveKeys()
        assertEquals("chiavi che nessuna riga mostra: $unused", emptySet<String>(), unused)
    }

    @Test
    fun everyValueThePageReadsExists() {
        val missing = SettingsSchema.referencedKeys() - SettingsApi.settingKeys - SettingsApi.derivedKeys
        assertEquals("valori che l'app non manda: $missing", emptySet<String>(), missing)
    }

    @Test
    fun everyListThePageAsksForIsSent() {
        val missing = SettingsSchema.dataKeys() - SettingsApi.dataKeys
        assertEquals("elenchi che l'app non manda: $missing", emptySet<String>(), missing)
    }

    @Test
    fun keysAndIdsAreUnique() {
        val switchKeys = rows.filterIsInstance<Switch>().map { it.key }
        assertEquals("interruttori ripetuti", switchKeys.size, switchKeys.toSet().size)
        val sections = SettingsSchema.sections(debug = true)
        assertEquals(sections.size, sections.map { it.id }.toSet().size)
        val groups = rows.filterIsInstance<Group>().map { it.id }
        assertEquals(groups.size, groups.toSet().size)
        val cards = sections.flatMap { it.cards }.mapNotNull { it.id }
        assertEquals(cards.size, cards.toSet().size)
        val choiceKeys = rows.filterIsInstance<Choice>().map { it.key }
        assertEquals(choiceKeys.size, choiceKeys.toSet().size)
    }

    @Test
    fun stepsReachTheirLimits() {
        rows.filterIsInstance<Stepper>().forEach { stepper ->
            assertTrue("${stepper.key}: passo", stepper.step > 0)
            assertTrue("${stepper.key}: limiti", stepper.min < stepper.max)
            // Dal minimo, a passi interi, si arriva al massimo (quando il massimo lo dà l'app non si sa)
            if (stepper.maxKey == null) assertEquals("${stepper.key}: il massimo non è un passo", 0, (stepper.max - stepper.min) % stepper.step)
        }
    }

    @Test
    fun choicesHaveTheirOptions() {
        rows.filterIsInstance<Choice>().forEach { choice ->
            if (choice.optionsData == null) {
                assertTrue("${choice.key}: nessuna voce", choice.options.isNotEmpty())
                assertEquals("${choice.key}: voci ripetute", choice.options.size, choice.options.map { it.value }.toSet().size)
                choice.options.forEach { assertTrue("${choice.key}/${it.value}: senza testo", it.label != 0 || !it.text.isNullOrEmpty()) }
            } else {
                assertTrue("${choice.key}: voci scritte e dell'app insieme", choice.options.isEmpty())
            }
        }
    }

    @Test
    fun switchesDescribeBothStatesOrNeither() {
        rows.filterIsInstance<Switch>().forEach {
            assertEquals("${it.key}: testo da acceso e da spento", it.descOn != 0, it.descOff != 0)
        }
    }

    @Test
    fun conditionsAskForOneThing() {
        val all = SettingsSchema.sections(debug = true).flatMap { section ->
            section.cards.flatMap { card -> card.showIf + SettingsSchema.flatten(card.rows).flatMap { it.showIf } }
        } + rows.filterIsInstance<Switch>().mapNotNull { it.blockedIf }
        all.forEach { assertTrue("${it.key}: una condizione sola", (it.eq != null) != (it.ne != null)) }
    }

    @Test
    fun actionListsHaveTheirOwnKey() {
        // Ogni elenco dei tasti si legge e si scrive con la stessa chiave (la pagina rimanda l'elenco com'è dopo il tocco)
        rows.filterIsInstance<Actions>().forEach { assertTrue(it.key in SettingsApi.settingKeys) }
    }

    @Test
    fun everyTextExistsInAllLanguages() {
        val res = resFolder()
        val names = stringNames()
        val base = readStrings(File(res, "values/strings.xml"))
        val languages = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
            .sortedBy { it.name }
        assertTrue("lingue trovate: ${languages.size}", languages.size >= 10)

        val problems = mutableListOf<String>()
        for (id in SettingsSchema.stringIds()) {
            val name = names[id] ?: run { problems += "id $id: nessuna stringa con questo id"; continue }
            val original = base[name]
            if (original == null) {
                problems += "$name: manca in values"
                continue
            }
            // Un testo che non si traduce (nomi propri, formati) sta solo nella lingua base
            if (!original.translatable) continue
            for (language in languages) {
                val translated = readStrings(File(language, "strings.xml"))[name]
                when {
                    translated == null -> problems += "$name: manca in ${language.name}"
                    placeholders(translated.text) != placeholders(original.text) ->
                        problems += "$name: segnaposto diversi in ${language.name}"
                }
            }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    // ------------------------------------------------------------------

    private class Entry(val text: String, val translatable: Boolean)

    private fun resFolder(): File =
        listOf(File("src/main/res"), File("browser/src/main/res")).first { it.isDirectory }

    /** Nome di ogni stringa per id, dalla classe R compilata. */
    private fun stringNames(): Map<Int, String> =
        Class.forName("com.fcaronte.aabrowser.R\$string").fields.associate { it.getInt(null) to it.name }

    private fun readStrings(file: File): Map<String, Entry> {
        val xml = file.readText()
        val found = mutableMapOf<String, Entry>()
        Regex("""<(string|plurals)\s+([^>]*?)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { match ->
            val attributes = match.groupValues[2]
            val name = Regex("""name="([^"]+)"""").find(attributes)?.groupValues?.get(1) ?: return@forEach
            val translatable = !attributes.contains("""translatable="false"""")
            found[name] = Entry(match.groupValues[3], translatable)
        }
        return found
    }

    /** I segnaposto di formato di un testo (%d, %s, %1$d…), in ordine, senza i %% . */
    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sdf]""").findAll(text).map { it.value.replace(Regex("""^%\d+\$"""), "%") }.toList()
}
