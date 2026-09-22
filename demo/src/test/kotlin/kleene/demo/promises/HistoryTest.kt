package kleene.demo.promises

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {

    private fun commit(git: Git, dir: File, path: String, text: String, at: Instant, message: String) {
        val file = dir.toPath().resolve(path)
        file.parent.createDirectories()
        file.writeText(text)
        git.add().addFilepattern(path).call()
        val who = PersonIdent("Terms Bot", "terms@example.com", at, ZoneOffset.UTC)
        git.commit().setMessage(message).setAuthor(who).setCommitter(who).call()
    }

    @Test
    fun `history returns versions of one path oldest to newest, excluding unrelated commits`(@TempDir dir: File) {
        val path = "Acme/Terms.md"
        val base = Instant.parse("2024-01-01T00:00:00Z")

        Git.init().setDirectory(dir).call().use { git ->
            commit(git, dir, path, "Version one.", base, "v1")
            commit(git, dir, "README.md", "Not the terms.", base.plus(1, ChronoUnit.DAYS), "unrelated")
            commit(git, dir, path, "Version two.", base.plus(2, ChronoUnit.DAYS), "v2")
            commit(git, dir, path, "Version three.", base.plus(3, ChronoUnit.DAYS), "v3")

            val versions = history(dir, path)

            assertEquals(3, versions.size)
            assertEquals(listOf("Version one.", "Version two.", "Version three."), versions.map { it.text })
            assertEquals(
                listOf(base, base.plus(2, ChronoUnit.DAYS), base.plus(3, ChronoUnit.DAYS)),
                versions.map { it.date },
            )
            assertEquals(versions.map { it.date }.sorted(), versions.map { it.date })
        }
    }

    @Test
    fun `history skips the commit that deletes the path`(@TempDir dir: File) {
        val path = "Acme/Terms.md"
        val base = Instant.parse("2024-01-01T00:00:00Z")

        Git.init().setDirectory(dir).call().use { git ->
            commit(git, dir, path, "Version one.", base, "v1")
            git.rm().addFilepattern(path).call()
            git.commit().setMessage("gone").call()

            assertEquals(listOf("Version one."), history(dir, path).map { it.text })
        }
    }
}
