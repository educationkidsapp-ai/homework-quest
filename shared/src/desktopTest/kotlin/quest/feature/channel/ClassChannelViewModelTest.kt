package quest.feature.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.ChannelPage
import quest.api.dto.ChannelPost
import quest.api.dto.ChatAttachment
import quest.api.dto.ChatPeerRole
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.feature.channel.domain.ClassChannelRepository
import quest.feature.channel.presentation.ClassChannelContract
import quest.feature.channel.presentation.ClassChannelViewModel
import quest.feature.children.domain.ChildrenRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 32: the **class channel** a parent reads — opened at its newest end, marked read by the act of opening,
 * paged backwards with `before`, and read-only: this view model has no send of any kind, because a parent's answer is
 * her own one-to-one thread with the post's author.
 */
class ClassChannelViewModelTest {

    private val maya = Child("c1", "Maya", "sky", Curriculum.BRITISH, 1)

    private class Kids(child: Child?) : ChildrenRepository {
        override val currentChild: StateFlow<Child?> = MutableStateFlow(child)
        override suspend fun refresh(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun children(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun select(id: String) {}
        override suspend fun clear() {}
    }

    private fun post(id: String, at: Long, author: String = "t-sara", deleted: Boolean? = null, files: List<ChatAttachment>? = null) =
        ChannelPost(
            id = id, authorId = author, authorName = if (author == "t-sara") "Ms. Sara" else "Ms. Noor",
            authorRole = ChatPeerRole.TEACHER, subject = "math", body = if (deleted == true) "" else "Post $id",
            attachments = files, createdAt = at, deleted = deleted,
        )

    /** The whole channel, newest last, paged exactly as the server pages it: `before` is exclusive. */
    private class Channel(private val all: List<ChannelPost>, private val unread: Int = 0) : ClassChannelRepository {
        val pagesAsked = mutableListOf<String?>()
        var readCalls = 0
        var fail: Throwable? = null

        private fun page(before: String?, limit: Int): ChannelPage {
            val upTo = before?.let { id -> all.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: all.size
            return ChannelPage(posts = all.take(upTo).takeLast(limit), unread = unread, classId = "sec-1a", className = "1A British")
        }

        override suspend fun page(childId: String, before: String?, limit: Int): ChannelPage {
            fail?.let { throw it }
            pagesAsked += before
            return page(before, limit)
        }

        override suspend fun markRead(childId: String): ChannelPage {
            fail?.let { throw it }
            readCalls++
            return page(null, ClassChannelRepository.PAGE_SIZE)
        }
    }

    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(kids: ChildrenRepository, channel: ClassChannelRepository) =
        ClassChannelViewModel(kids, channel).also { built += it }

    private suspend fun settle(state: StateFlow<ClassChannelContract.State>, predicate: (ClassChannelContract.State) -> Boolean): ClassChannelContract.State {
        repeat(400) { if (predicate(state.value)) return state.value; delay(5) }
        error("the channel never reached that state; last was ${state.value}")
    }

    private fun channelOf(n: Int) = (1..n).map { post("ch-$it", 1_758_000_000_000L + it * 60_000L) }

    // ---- 1. opening it: one request, which both reads the page and clears the badge

    @Test fun openingTheChannelMarksItReadAndIsOneRequestRatherThanTwo() = runBlocking {
        val channel = Channel(channelOf(3), unread = 2)
        val vm = viewModel(Kids(maya), channel)
        vm.dispatch(ClassChannelContract.Intent.Load)
        val state = settle(vm.state) { !it.loading }
        assertEquals(1, channel.readCalls)
        assertEquals(emptyList<String?>(), channel.pagesAsked.toList(), "the read answers her page, so no second GET")
        assertEquals(listOf("ch-1", "ch-2", "ch-3"), state.posts.map { it.id }, "oldest first, newest at the bottom")
        assertEquals("1A British", state.className)
        assertNull(state.errorMessage)
    }

    @Test fun withNoChildSelectedNothingIsAskedAtAll() = runBlocking {
        val channel = Channel(channelOf(2))
        val vm = viewModel(Kids(null), channel)
        vm.dispatch(ClassChannelContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertEquals(0, channel.readCalls)
        assertEquals(emptyList(), vm.state.value.posts)
    }

    // ---- 2. paging backwards: `before` the oldest post she has

    @Test fun earlierPostsAreOfferedOnlyWhileAFullPageCameBack() = runBlocking {
        val short = viewModel(Kids(maya), Channel(channelOf(3)))
        short.dispatch(ClassChannelContract.Intent.Load)
        assertTrue(!settle(short.state) { !it.loading }.hasEarlier, "three posts is the whole channel")

        val full = Channel(channelOf(ClassChannelRepository.PAGE_SIZE + 5))
        val vm = viewModel(Kids(maya), full)
        vm.dispatch(ClassChannelContract.Intent.Load)
        val first = settle(vm.state) { !it.loading }
        assertTrue(first.hasEarlier)
        assertEquals("ch-6", first.posts.first().id, "the newest page, not the oldest")
    }

    @Test fun earlierPostsArePrependedOnceAndPagedFromTheOldestSheHas() = runBlocking {
        val channel = Channel(channelOf(ClassChannelRepository.PAGE_SIZE + 5))
        val vm = viewModel(Kids(maya), channel)
        vm.dispatch(ClassChannelContract.Intent.Load)
        val first = settle(vm.state) { !it.loading }
        val oldestSeen = first.posts.first().id

        vm.dispatch(ClassChannelContract.Intent.LoadEarlier)
        val after = settle(vm.state) { it.posts.size > first.posts.size }
        assertEquals(listOf<String?>(oldestSeen), channel.pagesAsked.toList(), "paged before the oldest post she has")
        assertEquals("ch-1", after.posts.first().id)
        assertEquals(after.posts.map { it.id }.distinct().size, after.posts.size, "no post arrives twice")
        assertTrue(!after.hasEarlier, "a short page is the start of the channel")

        // Nothing more is offered, so nothing more is asked.
        vm.dispatch(ClassChannelContract.Intent.LoadEarlier)
        settle(vm.state) { !it.loadingEarlier }
        assertEquals(1, channel.pagesAsked.size)
    }

    // ---- 3. what a post carries: files, and a post that was taken down

    @Test fun aRemovedPostKeepsItsRowSoTheDatesAroundItStillRead() = runBlocking {
        val all = listOf(
            post("ch-1", 1_758_000_000_000L),
            post("ch-2", 1_758_000_060_000L, deleted = true),
            post("ch-3", 1_758_000_120_000L, author = "t-noor", files = listOf(ChatAttachment("a1", "image/jpeg", "board.jpg", 1_000))),
        )
        val vm = viewModel(Kids(maya), Channel(all))
        vm.dispatch(ClassChannelContract.Intent.Load)
        val state = settle(vm.state) { !it.loading }
        assertEquals(listOf("ch-1", "ch-2", "ch-3"), state.posts.map { it.id })
        val removed = state.posts[1]
        assertEquals(true, removed.deleted)
        assertEquals("", removed.body, "what it said does not come back")
        assertNull(removed.attachments)
        assertEquals("a1", state.posts[2].attachments?.single()?.id, "a photo is read as a message's is")
        // Reply privately goes to the author of *that* post, which is the only way a parent answers.
        assertEquals("t-noor", state.posts[2].authorId)
    }

    // ---- 4. a channel she cannot read

    @Test fun aSwitchedOffChannelSaysSoAndShowsNoPosts() = runBlocking {
        val channel = Channel(channelOf(2)).also { it.fail = ApiException(ApiError(ApiError.NOT_FOUND, "classChannel is off")) }
        val vm = viewModel(Kids(maya), channel)
        vm.dispatch(ClassChannelContract.Intent.Load)
        val state = settle(vm.state) { !it.loading }
        assertEquals(emptyList(), state.posts)
        assertTrue(state.errorMessage != null)
    }
}
