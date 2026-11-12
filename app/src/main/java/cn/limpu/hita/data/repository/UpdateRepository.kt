package cn.limpu.hita.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import javax.inject.Inject
import androidx.lifecycle.map
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.GitHubRelease
import cn.limpu.hita.data.model.ReleaseHistoryItem
import cn.limpu.hita.data.source.web.GitHubWebSource
import com.limpu.hitauser.data.model.CheckUpdateResult
import java.util.Locale

class UpdateRepository @Inject constructor(
    private val githubWebSource: GitHubWebSource
) {
    fun getReleaseHistory(
        updateUrl: String,
        allowPrerelease: Boolean,
        limit: Int = 10
    ): LiveData<DataState<List<ReleaseHistoryItem>>> {
        val repo = parseGitHubRepo(updateUrl)
            ?: return MutableLiveData(DataState(DataState.STATE.NOT_AVAILABLE))
        return githubWebSource.listReleases(repo.owner, repo.repo).map { state ->
            if (state.state != DataState.STATE.SUCCESS) {
                return@map DataState(state.state)
            }
            val releases = state.data ?: return@map DataState(DataState.STATE.FETCH_FAILED)
            val items = releases
                .asSequence()
                .filter { it.draft != true && (allowPrerelease || it.prerelease != true) }
                .mapNotNull { release ->
                    val version = ParsedVersion.parse(release.tagName ?: release.name)
                        ?: return@mapNotNull null
                    ReleaseHistoryItem(
                        versionName = version.displayName(),
                        releaseName = release.name ?: release.tagName ?: version.displayName(),
                        markdown = buildReleaseMarkdown(release),
                        htmlUrl = release.htmlUrl.orEmpty(),
                        prerelease = release.prerelease == true,
                    )
                }
                .take(limit)
                .toList()
            DataState(items)
        }
    }

    fun checkUpdateFromGitHub(
        currentVersionName: String,
        updateUrl: String,
        allowPrerelease: Boolean
    ): LiveData<DataState<CheckUpdateResult>>? {
        val repo = parseGitHubRepo(updateUrl) ?: return null
        return githubWebSource.listReleases(repo.owner, repo.repo).map { state ->
            if (state.state != DataState.STATE.SUCCESS) {
                return@map DataState(state.state)
            }
            val releases = state.data ?: return@map DataState(DataState.STATE.FETCH_FAILED)
            val current = ParsedVersion.parse(currentVersionName)
                ?: return@map DataState(DataState.STATE.FETCH_FAILED)
            val best = selectBestRelease(releases, allowPrerelease)
                ?: return@map DataState(DataState.STATE.FETCH_FAILED)
            val latestVersion = ParsedVersion.parse(best.tagName ?: best.name)
                ?: return@map DataState(DataState.STATE.FETCH_FAILED)

            val updateLogText = buildReleaseMarkdown(best)

            val apkAsset = best.assets?.find {
                it.name?.endsWith(".apk", ignoreCase = true) == true
            }
            val result = CheckUpdateResult().apply {
                latestVersionName = latestVersion.displayName()
                latestVersionCode = latestVersion.toVersionCode()
                latestUrl = best.htmlUrl ?: updateUrl
                downloadUrl = apkAsset?.downloadUrl ?: best.htmlUrl ?: updateUrl
                downloadCount = apkAsset?.downloadCount ?: 0
                this.updateLog = updateLogText
                shouldUpdate = latestVersion > current
            }
            DataState(result)
        }
    }

    fun checkUpdateWithFallback(
        currentVersionName: String,
        currentVersionCode: Long,
        builtInVersionName: String,
        builtInVersionCode: Long,
        updateUrl: String,
        updateLog: String,
        allowPrerelease: Boolean
    ): LiveData<DataState<CheckUpdateResult>> {
        val repo = parseGitHubRepo(updateUrl)
        if (repo == null) {
            return MutableLiveData(
                buildLocalResult(currentVersionCode, builtInVersionCode,
                    builtInVersionName, updateUrl, updateLog)
            )
        }
        val githubResult = githubWebSource.listReleases(repo.owner, repo.repo).map { state ->
            if (state.state != DataState.STATE.SUCCESS) {
                return@map DataState(state.state)
            }
            val releases = state.data ?: return@map DataState(DataState.STATE.FETCH_FAILED)
            val current = ParsedVersion.parse(currentVersionName)
                ?: return@map DataState(DataState.STATE.FETCH_FAILED)
            val best = selectBestRelease(releases, allowPrerelease)
                ?: return@map DataState(DataState.STATE.FETCH_FAILED)
            val latestVersion = ParsedVersion.parse(best.tagName ?: best.name)
                ?: return@map DataState(DataState.STATE.FETCH_FAILED)

            val updateLogText = buildReleaseMarkdown(best)

            val apkAsset = best.assets?.find {
                it.name?.endsWith(".apk", ignoreCase = true) == true
            }
            val result = CheckUpdateResult().apply {
                latestVersionName = latestVersion.displayName()
                latestVersionCode = latestVersion.toVersionCode()
                latestUrl = best.htmlUrl ?: updateUrl
                downloadUrl = apkAsset?.downloadUrl ?: best.htmlUrl ?: updateUrl
                downloadCount = apkAsset?.downloadCount ?: 0
                this.updateLog = updateLogText
                shouldUpdate = latestVersion > current
            }
            DataState(result)
        }
        val mediator = MediatorLiveData<DataState<CheckUpdateResult>>()
        mediator.addSource(githubResult) { state ->
            if (state.state == DataState.STATE.SUCCESS) {
                mediator.value = state
            } else {
                mediator.value = buildLocalResult(
                    currentVersionCode, builtInVersionCode,
                    builtInVersionName, updateUrl, updateLog
                )
            }
        }
        return mediator
    }

    private fun buildLocalResult(
        currentVersionCode: Long,
        builtInVersionCode: Long,
        builtInVersionName: String,
        updateUrl: String,
        updateLog: String
    ): DataState<CheckUpdateResult> {
        val result = CheckUpdateResult().apply {
            latestVersionCode = builtInVersionCode
            latestVersionName = builtInVersionName
            latestUrl = updateUrl
            this.updateLog = updateLog
            shouldUpdate = builtInVersionCode > currentVersionCode
        }
        return DataState(result)
    }

    private fun buildReleaseMarkdown(release: GitHubRelease): String {
        return buildString {
            if (release.prerelease == true) {
                append("预发布版本\n\n")
            }
            if (!release.body.isNullOrBlank()) {
                append(release.body.trim())
            } else {
                append("暂无更新说明")
            }
        }.trim()
    }

    private fun selectBestRelease(
        releases: List<GitHubRelease>,
        allowPrerelease: Boolean
    ): GitHubRelease? {
        val candidates = releases.filter { release ->
            release.draft != true && (allowPrerelease || release.prerelease != true)
        }
        var best: GitHubRelease? = null
        var bestVersion: ParsedVersion? = null
        for (release in candidates) {
            val version = ParsedVersion.parse(release.tagName ?: release.name) ?: continue
            if (bestVersion == null || version > bestVersion) {
                bestVersion = version
                best = release
            }
        }
        if (best == null || bestVersion == null) {
            return null
        }
        return best
    }

    private fun parseGitHubRepo(url: String): RepoInfo? {
        if (url.isBlank()) return null
        val match = Regex("github\\.com/([^/]+)/([^/]+)").find(url.lowercase(Locale.ROOT))
            ?: return null
        val owner = match.groupValues[1]
        var repo = match.groupValues[2]
        if (repo.endsWith(".git")) repo = repo.removeSuffix(".git")
        return RepoInfo(owner, repo)
    }

    private data class RepoInfo(val owner: String, val repo: String)

    internal data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preLabel: String?,
        val preNum: Int?
    ) : Comparable<ParsedVersion> {
        private fun preLabelRank(label: String): Int {
            return when (label.lowercase(Locale.ROOT)) {
                "alpha", "a" -> 1
                "beta", "b" -> 2
                "rc" -> 3
                else -> 4
            }
        }

        override fun compareTo(other: ParsedVersion): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            if (patch != other.patch) return patch.compareTo(other.patch)

            val thisPre = preLabel
            val otherPre = other.preLabel
            if (thisPre == null && otherPre == null) return 0
            if (thisPre == null) return 1
            if (otherPre == null) return -1

            val labelCmp = preLabelRank(thisPre).compareTo(preLabelRank(otherPre))
            if (labelCmp != 0) return labelCmp
            val nameCmp = thisPre.compareTo(otherPre)
            if (nameCmp != 0) return nameCmp

            val a = preNum ?: 0
            val b = other.preNum ?: 0
            return a.compareTo(b)
        }

        fun displayName(): String {
            val base = "$major.$minor.$patch"
            return if (preLabel == null) {
                base
            } else {
                if (preNum != null) "$base-$preLabel.$preNum" else "$base-$preLabel"
            }
        }

        fun toVersionCode(): Long {
            val base = major * 1_000_000 + minor * 1_000 + patch
            val suffix = if (preLabel == null) {
                999
            } else {
                val rank = preLabelRank(preLabel)
                val num = preNum?.coerceIn(0, 99) ?: 0
                rank * 100 + num
            }
            return base.toLong() * 1000 + suffix
        }

        companion object {
            private val coreRegex = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
            private val preRegex = Regex("-([0-9A-Za-z]+)(?:[\\.-]?(\\d+))?")

            /**
             * 只表示「这是正式发布」的后缀，不是预发布标签。
             *
             * GitHub 的 immutable releases 会把用过的 tag 名永久占用，误操作后只能改用
             * `v3.2.0-release` 这类 tag 重新发布。若把它当预发布，`toVersionCode()` 会退化、
             * `compareTo` 会认为它低于同号正式版，已装正式版的用户就再也收不到修复更新。
             */
            private val releaseMarkers = setOf("release", "final", "ga", "stable")

            fun parse(input: String?): ParsedVersion? {
                if (input.isNullOrBlank()) return null
                val core = coreRegex.find(input) ?: return null
                val major = core.groupValues[1].toIntOrNull() ?: return null
                val minor = core.groupValues[2].toIntOrNull() ?: return null
                val patch = core.groupValues[3].toIntOrNull() ?: 0
                val rest = input.substring(core.range.last + 1)
                val pre = preRegex.find(rest)
                val label = pre?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
                val num = pre?.groupValues?.getOrNull(2)?.toIntOrNull()
                // 发布标记按正式版处理：版本号与显示名都回到 major.minor.patch。
                if (label != null && label in releaseMarkers) {
                    return ParsedVersion(major, minor, patch, null, null)
                }
                return ParsedVersion(major, minor, patch, label, num)
            }
        }
    }
}
