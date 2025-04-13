import at.hannibal2.changelog.PullRequestCategory
import at.hannibal2.changelog.SkyHanniChangelogBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkyHanniChangelogBuilderTest {

    private val largeValidInput = listOf(
        "## Changelog New Features",
        "+ Added new feature. - John Doe",
        "    * Additional info.",
        "+ Added another feature. - Jane Doe",
        "    * More info.",
        "",
        "## Changelog Fixes",
        "+ Fixed a bug. - John Doe",
        "    * Additional info.",
        "+ Fixed another bug. - Jane Doe",
        "    * More info.",
        "",
        "## Changelog Technical Details",
        "+ Made some technical changes. - John Doe",
    )

    @Test
    fun `test body with large valid input`() {
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(largeValidInput, prLink)

        assertTrue(errors.isEmpty(), "Expected no errors")
        assertEquals(5, changes.size, "Expected five changes")
        assertEquals("Added new feature.", changes[0].text)
        assertEquals(PullRequestCategory.FEATURE, changes[0].category)
        assertEquals("John Doe", changes[0].author)
        assertEquals("Additional info.", changes[0].extraInfo[0])
        assertEquals("Added another feature.", changes[1].text)
        assertEquals(PullRequestCategory.FEATURE, changes[1].category)
        assertEquals("Jane Doe", changes[1].author)
        assertEquals("More info.", changes[1].extraInfo[0])
        assertEquals("Fixed a bug.", changes[2].text)
        assertEquals(PullRequestCategory.FIX, changes[2].category)
        assertEquals("John Doe", changes[2].author)
        assertEquals("Additional info.", changes[2].extraInfo[0])
        assertEquals("Fixed another bug.", changes[3].text)
        assertEquals(PullRequestCategory.FIX, changes[3].category)
        assertEquals("Jane Doe", changes[3].author)
        assertEquals("More info.", changes[3].extraInfo[0])
        assertEquals(PullRequestCategory.INTERNAL, changes[4].category)
        assertEquals("Made some technical changes.", changes[4].text)

    }

    @Test
    fun `test body with unknown category`() {
        val prBody = listOf(
            "## Changelog UnknownCategory",
            "+ Added new feature. - John Doe"
        )
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(prBody, prLink)

        assertTrue(errors.isNotEmpty(), "Expected errors")
        assertEquals("Unknown category: UnknownCategory", errors[0].message)
    }

    @Test
    fun `test body with missing author and extra information`() {
        val prBody = listOf(
            "## Changelog New Features",
            "+ Added new feature. - your_name_here",
            "    * Extra info."
        )
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(prBody, prLink)

        assertTrue(errors.isNotEmpty(), "Expected errors")
        assertEquals("Author is not set", errors[0].message)
        assertEquals("Extra info is not filled out", errors[1].message)
    }

    @Test
    fun `test body with illegal start`() {
        val prBody = listOf(
            "## Changelog New Features",
            "+ - Added new feature. - John Doe"
        )
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(prBody, prLink)

        assertTrue(errors.isNotEmpty(), "Expected errors")
        assertEquals("Illegal start of change line", errors[0].message)
    }

    @Test
    fun `test body with multiple formatting errors`() {
        val prBody = listOf(
            "## Changelog New Features",
            "+ Add new feature - John Doe",
        )
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(prBody, prLink)

        assertEquals(2, errors.size, "Expected two errors")
        assertEquals("Change should start with 'Added' instead of 'Add'", errors[0].message)
        assertEquals("Change should end with a full stop", errors[1].message)
    }

    @Test
    fun `test body with no author`() {
        val prBody = listOf(
            "## Changelog New Features",
            "+ Added new feature",
        )
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(prBody, prLink)

        assertEquals(2, errors.size, "Expected two errors")
        assertEquals("Author is not set", errors[0].message)
        assertEquals("Change should end with a full stop", errors[1].message)
    }


    @Test
    fun `test body should not have unknown lines after changes are declared`() {
        val prBody = listOf(
            "## Changelog New Features",
            "+ Added new feature. - John Doe",
            "This line should error"
        )
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(prBody, prLink)
        assertEquals(1, errors.size, "Expected one error")
        assertEquals("Unknown line after changes started being declared", errors[0].message)
    }

    @Test
    fun `test body with additional spaces`() {
        val prBody = listOf(
            "## Changelog New Features",
            "+ Added new feature.   -     John Doe",
            "    * More info.    ",
        )
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(prBody, prLink)

        println("changes: ${changes.map { it.text }}")
        println("errors: ${errors.map { it.message }}")

        assertTrue(errors.isEmpty(), "Expected no errors")
    }

    @Test
    fun `test body with empty line after category`() {
        val prBody = listOf(
            "## Changelog New Features",
            "",
            "- Added new feature. - John Doe",
            "    * More info.",
        )
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(prBody, prLink)

        assertTrue(errors.size == 1, "Expected one error")
        assertEquals("Unexpected empty line after category declared", errors[0].message)
    }

    @Test
    fun `test body with backticks`() {
        val prBody = listOf(
            "## Changelog New Features",
            "+ Added new feature to `MainClass`. - John Doe",
            "    * More info with `backticks`.",
        )
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(prBody, prLink)

        assertTrue(errors.size == 2, "Expected two errors")
        assertEquals("Change should not contain backticks", errors[0].message)
        assertEquals("Extra info should not contain backticks", errors[1].message)
    }

    @Test
    fun `test title with correct pull request title`() {
        val prTitle = "Feature + Fix: New feature"
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(largeValidInput, prLink)

        val pullRequestTitleErrors = SkyHanniChangelogBuilder.findPullRequestNameErrors(prTitle, changes)

        assertTrue(pullRequestTitleErrors.isEmpty(), "Expected no errors")
    }

    @Test
    fun `test title with unknown category`() {
        val prTitle = "Bugfix: Fixed a bug"
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(largeValidInput, prLink)

        val pullRequestTitleErrors = SkyHanniChangelogBuilder.findPullRequestNameErrors(prTitle, changes)

        assertEquals(1, pullRequestTitleErrors.size, "Expected one error")
        assertEquals("Unknown category: 'Bugfix', valid categories are: ${PullRequestCategory.validCategories()} " +
                "and expected categories based on your changes are: Feature, Fix, Backend", pullRequestTitleErrors[0].message)
    }

    @Test
    fun `test title with wrong format`() {
        val prTitle = "Feature new feature"
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(largeValidInput, prLink)

        val pullRequestTitleErrors = SkyHanniChangelogBuilder.findPullRequestNameErrors(prTitle, changes)

        assertEquals(1, pullRequestTitleErrors.size, "Expected one error")
        assertEquals("PR title does not match the expected format of 'Category: Title'", pullRequestTitleErrors[0].message)
    }

    @Test
    fun `test title with wrong category`() {
        val prTitle = "Remove: Remove a feature"
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(largeValidInput, prLink)

        val pullRequestTitleErrors = SkyHanniChangelogBuilder.findPullRequestNameErrors(prTitle, changes)

        assertEquals(1, pullRequestTitleErrors.size, "Expected one error")
        assertEquals("PR has category 'Remove' which is not in the changelog. Expected categories: Feature, Fix, Backend", pullRequestTitleErrors[0].message)
    }

    @Test
    fun `test title with another wrong format`() {
        val prTitle = "Feature/Fix: Fix up the backend"
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(largeValidInput, prLink)

        val pullRequestTitleErrors = SkyHanniChangelogBuilder.findPullRequestNameErrors(prTitle, changes)

        assertEquals(1, pullRequestTitleErrors.size, "Expected one error")
        assertEquals("PR categories shouldn't be separated by '/' or '&', use ' + ' instead", pullRequestTitleErrors[0].message)
    }

    @Test
    fun `test title with multiple categories must priorities fix category`() {
        val prTitle = "Feature + Backend: Fix up the backend"
        val prLink = "https://example.com/pr/1"

        val (changes, errors) = SkyHanniChangelogBuilder.findChanges(largeValidInput, prLink)

        val pullRequestTitleErrors = SkyHanniChangelogBuilder.findPullRequestNameErrors(prTitle, changes)

        assertEquals(1, pullRequestTitleErrors.size, "Expected one error")
        assertEquals("PR title must include category 'Fix' if there are any fixes in the PR", pullRequestTitleErrors[0].message)
    }
}