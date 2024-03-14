package org.example

import com.google.gson.annotations.SerializedName

data class PullRequest(
    val url: String,
    val id: Long,
    @SerializedName("node_id") val nodeId: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("diff_url") val diffUrl: String,
    @SerializedName("patch_url") val patchUrl: String,
    @SerializedName("issue_url") val issueUrl: String,
    val number: Int,
    val state: String,
    val locked: Boolean,
    val title: String,
    val user: User,
    val body: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("closed_at") val closedAt: String?,
    @SerializedName("merged_at") val mergedAt: String?,
    @SerializedName("merge_commit_sha") val mergeCommitSha: String?,
    val assignee: Any?, // Change type if you have specific assignee structure
    val assignees: List<Any>, // Change type if you have specific assignees structure
    @SerializedName("requested_reviewers") val requestedReviewers: List<Any>, // Change type if you have specific requested reviewers structure
    @SerializedName("requested_teams") val requestedTeams: List<Any>, // Change type if you have specific requested teams structure
    val labels: List<Any>, // Change type if you have specific labels structure
    val milestone: Milestone,
    val draft: Boolean,
    @SerializedName("commits_url") val commitsUrl: String,
    @SerializedName("review_comments_url") val reviewCommentsUrl: String,
    @SerializedName("review_comment_url") val reviewCommentUrl: String,
    @SerializedName("comments_url") val commentsUrl: String,
    @SerializedName("statuses_url") val statusesUrl: String,
    val head: Head,
    @SerializedName("author_association") val authorAssociation: String,
    @SerializedName("auto_merge") val autoMerge: Any?, // Change type if you have specific auto merge structure
    @SerializedName("active_lock_reason") val activeLockReason: Any? // Change type if you have specific active lock reason structure
)

data class User(
    val login: String,
    val id: Long,
    @SerializedName("node_id") val nodeId: String,
    @SerializedName("avatar_url") val avatarUrl: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("followers_url") val followersUrl: String,
    @SerializedName("following_url") val followingUrl: String,
    @SerializedName("gists_url") val gistsUrl: String,
    @SerializedName("starred_url") val starredUrl: String,
    @SerializedName("subscriptions_url") val subscriptionsUrl: String,
    @SerializedName("organizations_url") val organizationsUrl: String,
    @SerializedName("repos_url") val reposUrl: String,
    @SerializedName("events_url") val eventsUrl: String,
    @SerializedName("received_events_url") val receivedEventsUrl: String,
    val type: String,
    @SerializedName("site_admin") val siteAdmin: Boolean
)

data class Milestone(
    val url: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("labels_url") val labelsUrl: String,
    val id: Long,
    @SerializedName("node_id") val nodeId: String,
    val number: Int,
    val title: String,
    val description: String?,
    val creator: User,
    @SerializedName("open_issues") val openIssues: Int,
    @SerializedName("closed_issues") val closedIssues: Int,
    val state: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("due_on") val dueOn: String?,
    @SerializedName("closed_at") val closedAt: String?
)

data class Head(
    val label: String,
    val ref: String,
    val sha: String,
    val user: User,
    val repo: Repo
)

data class Repo(
    val id: Long,
    @SerializedName("node_id") val nodeId: String,
    val name: String,
    @SerializedName("full_name") val fullName: String,
    val private: Boolean,
    val owner: User,
    @SerializedName("html_url") val htmlUrl: String,
    val description: String,
    val fork: Boolean,
    val url: String,
    @SerializedName("forks_url") val forksUrl: String,
    @SerializedName("keys_url") val keysUrl: String,
    @SerializedName("collaborators_url") val collaboratorsUrl: String,
    @SerializedName("teams_url") val teamsUrl: String,
    @SerializedName("hooks_url") val hooksUrl: String,
    @SerializedName("issue_events_url") val issueEventsUrl: String,
    @SerializedName("events_url") val eventsUrl: String,
    @SerializedName("assignees_url") val assigneesUrl: String,
    @SerializedName("branches_url") val branchesUrl: String,
    @SerializedName("tags_url") val tagsUrl: String,
    @SerializedName("blobs_url") val blobsUrl: String,
    @SerializedName("git_tags_url") val gitTagsUrl: String,
    @SerializedName("git_refs_url") val gitRefsUrl: String,
    @SerializedName("trees_url") val treesUrl: String,
    @SerializedName("statuses_url") val statusesUrl: String,
    @SerializedName("languages_url") val languagesUrl: String,
    @SerializedName("stargazers_url") val stargazersUrl: String,
    @SerializedName("contributors_url") val contributorsUrl: String,
    @SerializedName("subscribers_url") val subscribersUrl: String,
    @SerializedName("subscription_url") val subscriptionUrl: String,
    @SerializedName("commits_url") val commitsUrl: String,
    @SerializedName("git_commits_url") val gitCommitsUrl: String,
    @SerializedName("comments_url") val commentsUrl: String,
    @SerializedName("issue_comment_url") val issueCommentUrl: String,
    @SerializedName("contents_url") val contentsUrl: String,
    @SerializedName("compare_url") val compareUrl: String,
    @SerializedName("merges_url") val mergesUrl: String,
    @SerializedName("archive_url") val archiveUrl: String,
    @SerializedName("downloads_url") val downloadsUrl: String,
    @SerializedName("issues_url") val issuesUrl: String,
    @SerializedName("pulls_url") val pullsUrl: String,
    @SerializedName("milestones_url") val milestonesUrl: String,
    @SerializedName("notifications_url") val notificationsUrl: String,
    @SerializedName("labels_url") val labelsUrl: String,
    @SerializedName("releases_url") val releasesUrl: String,
    @SerializedName("deployments_url") val deploymentsUrl: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("pushed_at") val pushedAt: String,
    @SerializedName("git_url") val gitUrl: String,
    @SerializedName("ssh_url") val sshUrl: String,
    @SerializedName("clone_url") val cloneUrl: String,
)