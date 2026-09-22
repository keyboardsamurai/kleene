package kleene.demo.promises

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.time.Instant

/** One committed version of a tracked file. [date] is the committer time. */
data class Version(val commit: String, val date: Instant, val text: String)

/**
 * The versions of [path] in the git repository at [repo], oldest to newest.
 *
 * Only commits that touched [path] are returned; a commit that deletes it is skipped. `text` is the UTF-8 blob
 * content at that commit.
 */
fun history(repo: File, path: String): List<Version> {
    // ponytail: no rename following; a renamed file ends its history at the rename, use JGit's FollowFilter to continue.
    Git.open(repo).use { git ->
        val repository = git.repository
        val commits = git.log().addPath(path).call().toList().asReversed()
        return commits.mapNotNull { commit ->
            val walk = TreeWalk.forPath(repository, path, commit.tree) ?: return@mapNotNull null
            val text = walk.use { String(repository.open(it.getObjectId(0)).bytes, Charsets.UTF_8) }
            Version(commit.name, commit.committerIdent.whenAsInstant, text)
        }
    }
}
