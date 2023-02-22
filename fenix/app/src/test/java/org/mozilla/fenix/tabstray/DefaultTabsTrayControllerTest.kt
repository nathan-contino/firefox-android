/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.tabstray

import androidx.navigation.NavController
import androidx.navigation.NavDirections
import androidx.navigation.NavOptions
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import mozilla.components.browser.state.selector.findTab
import mozilla.components.browser.state.selector.getNormalOrPrivateTabs
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.Tab
import mozilla.components.browser.storage.sync.TabEntry
import mozilla.components.concept.base.profiler.Profiler
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.service.glean.testing.GleanTestRule
import mozilla.components.support.test.libstate.ext.waitUntilIdle
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.BrowserDirection
import org.mozilla.fenix.GleanMetrics.Events
import org.mozilla.fenix.GleanMetrics.TabsTray
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingModeManager
import org.mozilla.fenix.components.AppStore
import org.mozilla.fenix.ext.maxActiveTime
import org.mozilla.fenix.ext.potentialInactiveTabs
import org.mozilla.fenix.helpers.FenixRobolectricTestRunner
import org.mozilla.fenix.home.HomeFragment
import org.mozilla.fenix.selection.SelectionHolder
import org.mozilla.fenix.utils.Settings
import java.util.concurrent.TimeUnit

@RunWith(FenixRobolectricTestRunner::class) // for gleanTestRule
class DefaultTabsTrayControllerTest {
    @MockK(relaxed = true)
    private lateinit var trayStore: TabsTrayStore

    @MockK(relaxed = true)
    private lateinit var browserStore: BrowserStore

    @MockK(relaxed = true)
    private lateinit var browsingModeManager: BrowsingModeManager

    @MockK(relaxed = true)
    private lateinit var navController: NavController

    @MockK(relaxed = true)
    private lateinit var profiler: Profiler

    @MockK(relaxed = true)
    private lateinit var navigationInteractor: NavigationInteractor

    @MockK(relaxed = true)
    private lateinit var tabsUseCases: TabsUseCases

    @MockK(relaxed = true)
    private lateinit var activity: HomeActivity

    private val appStore: AppStore = mockk(relaxed = true)
    private val settings: Settings = mockk(relaxed = true)

    @get:Rule
    val gleanTestRule = GleanTestRule(testContext)

    @Before
    fun setup() {
        MockKAnnotations.init(this)
    }

    @Test
    fun `GIVEN private mode WHEN the fab is clicked THEN a profile marker is added for the operations executed`() {
        profiler = spyk(profiler) {
            every { getProfilerTime() } returns Double.MAX_VALUE
        }

        assertNull(TabsTray.newPrivateTabTapped.testGetValue())

        createController().handlePrivateTabsFabClick()

        assertNotNull(TabsTray.newPrivateTabTapped.testGetValue())

        verifyOrder {
            profiler.getProfilerTime()
            navController.navigate(
                TabsTrayFragmentDirections.actionGlobalHome(focusOnAddressBar = true),
            )
            navigationInteractor.onTabTrayDismissed()
            profiler.addMarker(
                "DefaultTabTrayController.onNewTabTapped",
                Double.MAX_VALUE,
            )
        }
    }

    @Test
    fun `GIVEN normal mode WHEN the fab is clicked THEN a profile marker is added for the operations executed`() {
        profiler = spyk(profiler) {
            every { getProfilerTime() } returns Double.MAX_VALUE
        }

        createController().handleNormalTabsFabClick()

        verifyOrder {
            profiler.getProfilerTime()
            navController.navigate(
                TabsTrayFragmentDirections.actionGlobalHome(focusOnAddressBar = true),
            )
            navigationInteractor.onTabTrayDismissed()
            profiler.addMarker(
                "DefaultTabTrayController.onNewTabTapped",
                Double.MAX_VALUE,
            )
        }
    }

    @Test
    fun `GIVEN private mode WHEN the fab is clicked THEN Event#NewPrivateTabTapped is added to telemetry`() {
        assertNull(TabsTray.newPrivateTabTapped.testGetValue())

        createController().handlePrivateTabsFabClick()

        assertNotNull(TabsTray.newPrivateTabTapped.testGetValue())
    }

    @Test
    fun `GIVEN normal mode WHEN the fab is clicked THEN Event#NewTabTapped is added to telemetry`() {
        assertNull(TabsTray.newTabTapped.testGetValue())

        createController().handleNormalTabsFabClick()

        assertNotNull(TabsTray.newTabTapped.testGetValue())
    }

    @Test
    fun `GIVEN the user is on the synced tabs page WHEN the fab is clicked THEN fire off a sync action`() {
        every { trayStore.state.syncing } returns false

        createController().handleSyncedTabsFabClick()

        verify { trayStore.dispatch(TabsTrayAction.SyncNow) }
    }

    @Test
    fun `GIVEN the user is on the synced tabs page and there is already an active sync WHEN the fab is clicked THEN no action should be taken`() {
        every { trayStore.state.syncing } returns true

        createController().handleSyncedTabsFabClick()

        verify(exactly = 0) { trayStore.dispatch(TabsTrayAction.SyncNow) }
    }

    @Test
    fun `WHEN handleTabDeletion is called THEN Event#ClosedExistingTab is added to telemetry`() {
        val tab: TabSessionState = mockk { every { content.private } returns true }
        assertNull(TabsTray.closedExistingTab.testGetValue())

        every { browserStore.state } returns mockk()
        try {
            mockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
            every { browserStore.state.findTab(any()) } returns tab
            every { browserStore.state.getNormalOrPrivateTabs(any()) } returns listOf(tab)

            createController().handleTabDeletion("testTabId", "unknown")
            assertNotNull(TabsTray.closedExistingTab.testGetValue())
        } finally {
            unmockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
        }
    }

    @Test
    fun `GIVEN active private download WHEN handleTabDeletion is called for the last private tab THEN showCancelledDownloadWarning is called`() {
        var showCancelledDownloadWarningInvoked = false
        val controller = spyk(
            createController(
                showCancelledDownloadWarning = { _, _, _ ->
                    showCancelledDownloadWarningInvoked = true
                },
            ),
        )
        val tab: TabSessionState = mockk { every { content.private } returns true }
        every { browserStore.state } returns mockk()
        every { browserStore.state.downloads } returns mapOf(
            "1" to DownloadState(
                "https://mozilla.org/download",
                private = true,
                destinationDirectory = "Download",
                status = DownloadState.Status.DOWNLOADING,
            ),
        )
        try {
            mockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
            every { browserStore.state.findTab(any()) } returns tab
            every { browserStore.state.getNormalOrPrivateTabs(any()) } returns listOf(tab)

            controller.handleTabDeletion("testTabId", "unknown")

            assertTrue(showCancelledDownloadWarningInvoked)
        } finally {
            unmockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
        }
    }

    @Test
    fun `WHEN handleTrayScrollingToPosition is called with smoothScroll=true THEN it scrolls to that position with smoothScroll`() {
        var selectTabPositionInvoked = false
        createController(
            selectTabPosition = { position, smoothScroll ->
                assertEquals(3, position)
                assertTrue(smoothScroll)
                selectTabPositionInvoked = true
            },
        ).handleTrayScrollingToPosition(3, true)

        assertTrue(selectTabPositionInvoked)
    }

    @Test
    fun `WHEN handleTrayScrollingToPosition is called with smoothScroll=true THEN it emits an action for the tray page of that tab position`() {
        createController().handleTrayScrollingToPosition(33, true)

        verify { trayStore.dispatch(TabsTrayAction.PageSelected(Page.positionToPage(33))) }
    }

    @Test
    fun `WHEN handleTrayScrollingToPosition is called with smoothScroll=false THEN it emits an action for the tray page of that tab position`() {
        createController().handleTrayScrollingToPosition(44, true)

        verify { trayStore.dispatch(TabsTrayAction.PageSelected(Page.positionToPage(44))) }
    }

    @Test
    fun `GIVEN already on browserFragment WHEN handleNavigateToBrowser is called THEN the tray is dismissed`() {
        every { navController.currentDestination?.id } returns R.id.browserFragment

        var dismissTrayInvoked = false
        createController(dismissTray = { dismissTrayInvoked = true }).handleNavigateToBrowser()

        assertTrue(dismissTrayInvoked)
        verify(exactly = 0) { navController.popBackStack() }
        verify(exactly = 0) { navController.popBackStack(any<Int>(), any()) }
        verify(exactly = 0) { navController.navigate(any<Int>()) }
        verify(exactly = 0) { navController.navigate(any<NavDirections>()) }
        verify(exactly = 0) { navController.navigate(any<NavDirections>(), any<NavOptions>()) }
    }

    @Test
    fun `GIVEN not already on browserFragment WHEN handleNavigateToBrowser is called THEN the tray is dismissed and popBackStack is executed`() {
        every { navController.currentDestination?.id } returns R.id.browserFragment + 1
        every { navController.popBackStack(R.id.browserFragment, false) } returns true

        var dismissTrayInvoked = false
        createController(dismissTray = { dismissTrayInvoked = true }).handleNavigateToBrowser()

        assertTrue(dismissTrayInvoked)
        verify { navController.popBackStack(R.id.browserFragment, false) }
        verify(exactly = 0) { navController.navigate(any<Int>()) }
        verify(exactly = 0) { navController.navigate(any<NavDirections>()) }
        verify(exactly = 0) { navController.navigate(any<NavDirections>(), any<NavOptions>()) }
    }

    @Test
    fun `GIVEN not already on browserFragment WHEN handleNavigateToBrowser is called and popBackStack fails THEN it navigates to browserFragment`() {
        every { navController.currentDestination?.id } returns R.id.browserFragment + 1
        every { navController.popBackStack(R.id.browserFragment, false) } returns false

        var dismissTrayInvoked = false
        createController(dismissTray = { dismissTrayInvoked = true }).handleNavigateToBrowser()

        assertTrue(dismissTrayInvoked)
        verify { navController.popBackStack(R.id.browserFragment, false) }
        verify { navController.navigate(R.id.browserFragment) }
    }

    @Test
    fun `GIVEN not already on browserFragment WHEN handleNavigateToBrowser is called and popBackStack succeeds THEN the method finishes`() {
        every { navController.popBackStack(R.id.browserFragment, false) } returns true

        var dismissTrayInvoked = false
        createController(dismissTray = { dismissTrayInvoked = true }).handleNavigateToBrowser()

        assertTrue(dismissTrayInvoked)
        verify(exactly = 1) { navController.popBackStack(R.id.browserFragment, false) }
        verify(exactly = 0) { navController.navigate(R.id.browserFragment) }
    }

    @Test
    fun `GIVEN more tabs opened WHEN handleTabDeletion is called THEN that tab is removed and an undo snackbar is shown`() {
        val tab: TabSessionState = mockk {
            every { content } returns mockk()
            every { content.private } returns true
        }
        every { browserStore.state } returns mockk()
        try {
            mockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
            every { browserStore.state.findTab(any()) } returns tab
            every { browserStore.state.getNormalOrPrivateTabs(any()) } returns listOf(tab, mockk())

            var showUndoSnackbarForTabInvoked = false
            createController(
                showUndoSnackbarForTab = {
                    assertTrue(it)
                    showUndoSnackbarForTabInvoked = true
                },
            ).handleTabDeletion("22")

            verify { tabsUseCases.removeTab("22") }
            assertTrue(showUndoSnackbarForTabInvoked)
        } finally {
            unmockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
        }
    }

    @Test
    fun `GIVEN only one tab opened WHEN handleTabDeletion is called THEN that it navigates to home where the tab will be removed`() {
        var showUndoSnackbarForTabInvoked = false
        val controller = spyk(createController(showUndoSnackbarForTab = { showUndoSnackbarForTabInvoked = true }))
        val tab: TabSessionState = mockk {
            every { content } returns mockk()
            every { content.private } returns true
        }
        every { browserStore.state } returns mockk()
        try {
            mockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
            every { browserStore.state.findTab(any()) } returns tab
            every { browserStore.state.getNormalOrPrivateTabs(any()) } returns listOf(tab)

            controller.handleTabDeletion("33")

            verify { controller.dismissTabsTrayAndNavigateHome("33") }
            verify(exactly = 0) { tabsUseCases.removeTab(any()) }
            assertFalse(showUndoSnackbarForTabInvoked)
        } finally {
            unmockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
        }
    }

    @Test
    fun `WHEN handleMultipleTabsDeletion is called to close all private tabs THEN that it navigates to home where that tabs will be removed and shows undo snackbar`() {
        var showUndoSnackbarForTabInvoked = false
        val controller = spyk(
            createController(
                showUndoSnackbarForTab = {
                    assertTrue(it)
                    showUndoSnackbarForTabInvoked = true
                },
            ),
        )

        val privateTab = createTab(url = "url", private = true)

        every { browserStore.state } returns mockk()
        try {
            mockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
            every { browserStore.state.getNormalOrPrivateTabs(any()) } returns listOf(mockk(), mockk())

            controller.handleMultipleTabsDeletion(listOf(privateTab, mockk()))

            assertNotNull(TabsTray.closeSelectedTabs.testGetValue())
            val snapshot = TabsTray.closeSelectedTabs.testGetValue()!!
            assertEquals(1, snapshot.size)
            assertEquals("2", snapshot.single().extra?.getValue("tab_count"))

            verify { controller.dismissTabsTrayAndNavigateHome(HomeFragment.ALL_PRIVATE_TABS) }
            assertTrue(showUndoSnackbarForTabInvoked)
            verify(exactly = 0) { tabsUseCases.removeTabs(any()) }
        } finally {
            unmockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
        }
    }

    @Test
    fun `WHEN handleMultipleTabsDeletion is called to close all normal tabs THEN that it navigates to home where that tabs will be removed and shows undo snackbar`() {
        var showUndoSnackbarForTabInvoked = false
        val controller = spyk(
            createController(
                showUndoSnackbarForTab = {
                    assertFalse(it)
                    showUndoSnackbarForTabInvoked = true
                },
            ),
        )

        val normalTab = createTab(url = "url", private = false)

        every { browserStore.state } returns mockk()
        try {
            mockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
            every { browserStore.state.getNormalOrPrivateTabs(any()) } returns listOf(mockk(), mockk())

            controller.handleMultipleTabsDeletion(listOf(normalTab, normalTab))

            assertNotNull(TabsTray.closeSelectedTabs.testGetValue())
            val snapshot = TabsTray.closeSelectedTabs.testGetValue()!!
            assertEquals(1, snapshot.size)
            assertEquals("2", snapshot.single().extra?.getValue("tab_count"))

            verify { controller.dismissTabsTrayAndNavigateHome(HomeFragment.ALL_NORMAL_TABS) }
            verify(exactly = 0) { tabsUseCases.removeTabs(any()) }
            assertTrue(showUndoSnackbarForTabInvoked)
        } finally {
            unmockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
        }
    }

    @Test
    fun `WHEN handleMultipleTabsDeletion is called to close some private tabs THEN that it uses tabsUseCases#removeTabs and shows an undo snackbar`() {
        var showUndoSnackbarForTabInvoked = false
        val controller = spyk(createController(showUndoSnackbarForTab = { showUndoSnackbarForTabInvoked = true }))
        val privateTab = createTab(id = "42", url = "url", private = true)

        every { browserStore.state } returns mockk()
        try {
            mockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
            every { browserStore.state.getNormalOrPrivateTabs(any()) } returns listOf(mockk(), mockk())

            controller.handleMultipleTabsDeletion(listOf(privateTab))

            assertNotNull(TabsTray.closeSelectedTabs.testGetValue())
            val snapshot = TabsTray.closeSelectedTabs.testGetValue()!!
            assertEquals(1, snapshot.size)
            assertEquals("1", snapshot.single().extra?.getValue("tab_count"))

            verify { tabsUseCases.removeTabs(listOf("42")) }
            verify(exactly = 0) { controller.dismissTabsTrayAndNavigateHome(any()) }
            assertTrue(showUndoSnackbarForTabInvoked)
        } finally {
            unmockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
        }
    }

    @Test
    fun `WHEN handleMultipleTabsDeletion is called to close some normal tabs THEN that it uses tabsUseCases#removeTabs and shows an undo snackbar`() {
        var showUndoSnackbarForTabInvoked = false
        val controller = spyk(createController(showUndoSnackbarForTab = { showUndoSnackbarForTabInvoked = true }))
        val privateTab = createTab(id = "24", url = "url", private = false)

        every { browserStore.state } returns mockk()
        try {
            mockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
            every { browserStore.state.getNormalOrPrivateTabs(any()) } returns listOf(mockk(), mockk())

            controller.handleMultipleTabsDeletion(listOf(privateTab))

            assertNotNull(TabsTray.closeSelectedTabs.testGetValue())
            val snapshot = TabsTray.closeSelectedTabs.testGetValue()!!
            assertEquals(1, snapshot.size)
            assertEquals("1", snapshot.single().extra?.getValue("tab_count"))

            verify { tabsUseCases.removeTabs(listOf("24")) }
            verify(exactly = 0) { controller.dismissTabsTrayAndNavigateHome(any()) }
            assertTrue(showUndoSnackbarForTabInvoked)
        } finally {
            unmockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
        }
    }

    @Test
    fun `GIVEN private mode selected WHEN sendNewTabEvent is called THEN NewPrivateTabTapped is tracked in telemetry`() {
        createController().sendNewTabEvent(true)

        assertNotNull(TabsTray.newPrivateTabTapped.testGetValue())
    }

    @Test
    fun `GIVEN normal mode selected WHEN sendNewTabEvent is called THEN NewTabTapped is tracked in telemetry`() {
        assertNull(TabsTray.newTabTapped.testGetValue())

        createController().sendNewTabEvent(false)

        assertNotNull(TabsTray.newTabTapped.testGetValue())
    }

    @Test
    fun `WHEN dismissTabsTrayAndNavigateHome is called with a specific tab id THEN tray is dismissed and navigates home is opened to delete that tab`() {
        var dismissTrayInvoked = false
        var navigateToHomeAndDeleteSessionInvoked = false
        createController(
            dismissTray = {
                dismissTrayInvoked = true
            },
            navigateToHomeAndDeleteSession = {
                assertEquals("randomId", it)
                navigateToHomeAndDeleteSessionInvoked = true
            },
        ).dismissTabsTrayAndNavigateHome("randomId")

        assertTrue(dismissTrayInvoked)
        assertTrue(navigateToHomeAndDeleteSessionInvoked)
    }

    @Test
    fun `WHEN a synced tab is clicked THEN the metrics are reported and the tab is opened`() {
        val tab = mockk<Tab>()
        val entry = mockk<TabEntry>()
        assertNull(Events.syncedTabOpened.testGetValue())

        every { tab.active() }.answers { entry }
        every { entry.url }.answers { "https://mozilla.org" }

        var dismissTabTrayInvoked = false
        createController(
            dismissTray = {
                dismissTabTrayInvoked = true
            },
        ).handleSyncedTabClicked(tab)

        assertTrue(dismissTabTrayInvoked)
        assertNotNull(Events.syncedTabOpened.testGetValue())

        verify {
            activity.openToBrowserAndLoad(
                searchTermOrURL = "https://mozilla.org",
                newTab = true,
                from = BrowserDirection.FromTabsTray,
            )
        }
    }

    @Test
    fun `GIVEN no tabs selected and the user is not in multi select mode WHEN the user long taps a tab THEN that tab will become selected`() {
        trayStore = TabsTrayStore()
        val selectionHolder: SelectionHolder<TabSessionState> = mockk {
            every { selectedItems } returns emptySet()
        }
        val controller = spyk(createController())
        val tab1 = TabSessionState(
            id = "1",
            content = ContentState(
                url = "www.mozilla.com",
            ),
        )
        val tab2 = TabSessionState(
            id = "2",
            content = ContentState(
                url = "www.google.com",
            ),
        )
        trayStore.dispatch(TabsTrayAction.ExitSelectMode)
        trayStore.waitUntilIdle()

        controller.handleMultiSelectClicked(tab1, selectionHolder, "Tabs tray")
        verify(exactly = 1) { controller.handleTabSelected(tab1, "Tabs tray") }

        controller.handleMultiSelectClicked(tab2, selectionHolder, "Tabs tray")
        verify(exactly = 1) { controller.handleTabSelected(tab2, "Tabs tray") }
    }

    @Test
    fun `GIVEN the user is in multi select mode and a tab is selected WHEN the user taps the selected tab THEN the tab will become unselected`() {
        trayStore = TabsTrayStore()
        val tab1 = TabSessionState(
            id = "1",
            content = ContentState(
                url = "www.mozilla.com",
            ),
        )
        val tab2 = TabSessionState(
            id = "2",
            content = ContentState(
                url = "www.google.com",
            ),
        )
        val selectionHolder: SelectionHolder<TabSessionState> = mockk {
            every { selectedItems } returns setOf(tab1, tab2)
        }
        val controller = spyk(createController())
        trayStore.dispatch(TabsTrayAction.EnterSelectMode)
        trayStore.waitUntilIdle()

        controller.handleMultiSelectClicked(tab1, selectionHolder, "Tabs tray")
        verify(exactly = 1) { controller.handleTabUnselected(tab1) }

        controller.handleMultiSelectClicked(tab2, selectionHolder, "Tabs tray")
        verify(exactly = 1) { controller.handleTabUnselected(tab2) }
    }

    @Test
    fun `GIVEN at least a tab is selected and the user is in multi select mode WHEN the user taps a tab THEN that tab will become selected`() {
        trayStore = spyk(TabsTrayStore())
        trayStore.dispatch(TabsTrayAction.EnterSelectMode)
        trayStore.waitUntilIdle()
        val controller = spyk(createController())
        val tab1 = TabSessionState(
            id = "1",
            content = ContentState(
                url = "www.mozilla.com",
            ),
        )
        val selectionHolder: SelectionHolder<TabSessionState> = mockk {
            every { selectedItems } returns setOf(tab1)
        }
        val tab2 = TabSessionState(
            id = "2",
            content = ContentState(
                url = "www.google.com",
            ),
        )

        controller.handleMultiSelectClicked(tab2, selectionHolder, "Tabs tray")

        verify(exactly = 1) { trayStore.dispatch(TabsTrayAction.AddSelectTab(tab2)) }
    }

    @Test
    fun `GIVEN the user selects only the current tab WHEN the user forces tab to be inactive THEN tab does not become inactive`() {
        val currentTab = TabSessionState(content = mockk(), id = "currentTab", createdAt = 11)
        val secondTab = TabSessionState(content = mockk(), id = "secondTab", createdAt = 22)
        browserStore = BrowserStore(
            initialState = BrowserState(
                tabs = listOf(currentTab, secondTab),
                selectedTabId = currentTab.id,
            ),
        )

        createController().forceTabsAsInactive(listOf(currentTab), 5)
        browserStore.waitUntilIdle()

        val updatedCurrentTab = browserStore.state.tabs.first { it.id == currentTab.id }
        assertEquals(updatedCurrentTab, currentTab)
        val updatedSecondTab = browserStore.state.tabs.first { it.id == secondTab.id }
        assertEquals(updatedSecondTab, secondTab)
    }

    @Test
    fun `GIVEN the user selects multiple tabs including the current tab WHEN the user forces them all to be inactive THEN all but current tab become inactive`() {
        val currentTab = TabSessionState(content = mockk(), id = "currentTab", createdAt = 11)
        val secondTab = TabSessionState(content = mockk(), id = "secondTab", createdAt = 22)
        browserStore = BrowserStore(
            initialState = BrowserState(
                tabs = listOf(currentTab, secondTab),
                selectedTabId = currentTab.id,
            ),
        )

        createController().forceTabsAsInactive(listOf(currentTab, secondTab), 5)
        browserStore.waitUntilIdle()

        val updatedCurrentTab = browserStore.state.tabs.first { it.id == currentTab.id }
        assertEquals(updatedCurrentTab, currentTab)
        val updatedSecondTab = browserStore.state.tabs.first { it.id == secondTab.id }
        assertNotEquals(updatedSecondTab, secondTab)
        val expectedTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)
        // Account for System.currentTimeMillis() giving different values in test vs the system under test
        // and also for the waitUntilIdle to block for even hundreds of milliseconds.
        assertTrue(updatedSecondTab.lastAccess in (expectedTime - 5000)..expectedTime)
        assertTrue(updatedSecondTab.createdAt in (expectedTime - 5000)..expectedTime)
    }

    @Test
    fun `GIVEN no value is provided for inactive days WHEN forcing tabs as inactive THEN set their last active time 15 days ago`() {
        val controller = spyk(createController())
        every { browserStore.state.selectedTabId } returns "test"

        controller.forceTabsAsInactive(emptyList())

        verify {
            controller.forceTabsAsInactive(emptyList(), 15L)
        }
    }

    fun `WHEN the inactive tabs section is expanded THEN the expanded telemetry event should be reported`() {
        val controller = createController()

        assertNull(TabsTray.inactiveTabsExpanded.testGetValue())
        assertNull(TabsTray.inactiveTabsCollapsed.testGetValue())

        controller.handleInactiveTabsHeaderClicked(expanded = true)

        assertNotNull(TabsTray.inactiveTabsExpanded.testGetValue())
        assertNull(TabsTray.inactiveTabsCollapsed.testGetValue())
    }

    @Test
    fun `WHEN the inactive tabs section is collapsed THEN the collapsed telemetry event should be reported`() {
        val controller = createController()

        assertNull(TabsTray.inactiveTabsExpanded.testGetValue())
        assertNull(TabsTray.inactiveTabsCollapsed.testGetValue())

        controller.handleInactiveTabsHeaderClicked(expanded = false)

        assertNull(TabsTray.inactiveTabsExpanded.testGetValue())
        assertNotNull(TabsTray.inactiveTabsCollapsed.testGetValue())
    }

    @Test
    fun `WHEN the inactive tabs auto-close feature prompt is dismissed THEN update settings and report the telemetry event`() {
        val controller = spyk(createController())

        assertNull(TabsTray.autoCloseDimissed.testGetValue())

        controller.handleInactiveTabsAutoCloseDialogDismiss()

        assertNotNull(TabsTray.autoCloseDimissed.testGetValue())
        verify { settings.hasInactiveTabsAutoCloseDialogBeenDismissed = true }
    }

    @Test
    fun `WHEN the inactive tabs auto-close feature prompt is accepted THEN update settings and report the telemetry event`() {
        val controller = spyk(createController())

        assertNull(TabsTray.autoCloseTurnOnClicked.testGetValue())

        controller.handleEnableInactiveTabsAutoCloseClicked()

        assertNotNull(TabsTray.autoCloseTurnOnClicked.testGetValue())

        verify { settings.closeTabsAfterOneMonth = true }
        verify { settings.closeTabsAfterOneWeek = false }
        verify { settings.closeTabsAfterOneDay = false }
        verify { settings.manuallyCloseTabs = false }
        verify { settings.hasInactiveTabsAutoCloseDialogBeenDismissed = true }
    }

    @Test
    fun `WHEN an inactive tab is selected THEN report the telemetry event and open the tab`() {
        val controller = spyk(createController())
        val tab = TabSessionState(
            id = "tabId",
            content = ContentState(
                url = "www.mozilla.com",
            ),
        )

        every { controller.handleTabSelected(any(), any()) } just runs

        assertNull(TabsTray.openInactiveTab.testGetValue())

        controller.handleInactiveTabClicked(tab)

        assertNotNull(TabsTray.openInactiveTab.testGetValue())

        verify { controller.handleTabSelected(tab, TrayPagerAdapter.INACTIVE_TABS_FEATURE_NAME) }
    }

    @Test
    fun `WHEN an inactive tab is closed THEN report the telemetry event and delete the tab`() {
        val controller = spyk(createController())
        val tab = TabSessionState(
            id = "tabId",
            content = ContentState(
                url = "www.mozilla.com",
            ),
        )

        every { controller.handleTabDeletion(any(), any()) } just runs

        assertNull(TabsTray.closeInactiveTab.testGetValue())

        controller.handleCloseInactiveTabClicked(tab)

        assertNotNull(TabsTray.closeInactiveTab.testGetValue())

        verify { controller.handleTabDeletion(tab.id, TrayPagerAdapter.INACTIVE_TABS_FEATURE_NAME) }
    }

    @Test
    fun `WHEN all inactive tabs are closed THEN perform the deletion and report the telemetry event and show a Snackbar`() {
        var showSnackbarInvoked = false
        val controller = createController(
            showUndoSnackbarForTab = {
                showSnackbarInvoked = true
            },
        )
        val inactiveTab: TabSessionState = mockk {
            every { lastAccess } returns maxActiveTime
            every { createdAt } returns 0
            every { id } returns "24"
            every { content } returns mockk {
                every { private } returns false
            }
        }

        try {
            mockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
            every { browserStore.state } returns mockk()
            every { browserStore.state.potentialInactiveTabs } returns listOf(inactiveTab)
            assertNull(TabsTray.closeAllInactiveTabs.testGetValue())

            controller.handleDeleteAllInactiveTabsClicked()

            verify { tabsUseCases.removeTabs(listOf("24")) }
            assertNotNull(TabsTray.closeAllInactiveTabs.testGetValue())
            assertTrue(showSnackbarInvoked)
        } finally {
            unmockkStatic("mozilla.components.browser.state.selector.SelectorsKt")
        }
    }

    fun `WHEN a tab is selected THEN report the metric, update the state, and open the browser`() {
        val controller = spyk(createController())
        val tab = TabSessionState(
            id = "tabId",
            content = ContentState(
                url = "www.mozilla.com",
            ),
        )
        val source = TrayPagerAdapter.INACTIVE_TABS_FEATURE_NAME

        every { controller.handleNavigateToBrowser() } just runs

        assertNull(TabsTray.openedExistingTab.testGetValue())

        controller.handleTabSelected(tab, source)

        assertNotNull(TabsTray.openedExistingTab.testGetValue())
        val snapshot = TabsTray.openedExistingTab.testGetValue()!!
        assertEquals(1, snapshot.size)
        assertEquals(source, snapshot.single().extra?.getValue("opened_existing_tab"))

        verify { tabsUseCases.selectTab(tab.id) }
        verify { controller.handleNavigateToBrowser() }
    }

    fun `WHEN a tab is selected without a source THEN report the metric with an unknown source, update the state, and open the browser`() {
        val controller = spyk(createController())
        val tab = TabSessionState(
            id = "tabId",
            content = ContentState(
                url = "www.mozilla.com",
            ),
        )
        val sourceText = "unknown"

        every { controller.handleNavigateToBrowser() } just runs

        assertNull(TabsTray.openedExistingTab.testGetValue())

        controller.handleTabSelected(tab, null)

        assertNotNull(TabsTray.openedExistingTab.testGetValue())
        val snapshot = TabsTray.openedExistingTab.testGetValue()!!
        assertEquals(1, snapshot.size)
        assertEquals(sourceText, snapshot.single().extra?.getValue("opened_existing_tab"))

        verify { tabsUseCases.selectTab(tab.id) }
        verify { controller.handleNavigateToBrowser() }
    }

    @Test
    fun `GIVEN a normal tab is selected WHEN the last private tab is deleted THEN that private tab is removed and an undo snackbar is shown and original normal tab is still displayed`() {
        val currentTab = TabSessionState(content = ContentState(url = "https://simulate.com", private = false), id = "currentTab")
        val privateTab = TabSessionState(content = ContentState(url = "https://mozilla.com", private = true), id = "privateTab")
        var showUndoSnackbarForTabInvoked = false
        var navigateToHomeAndDeleteSessionInvoked = false
        browserStore = BrowserStore(
            initialState = BrowserState(
                tabs = listOf(currentTab, privateTab),
                selectedTabId = currentTab.id,
            ),
        )
        val controller = spyk(
            createController(
                showUndoSnackbarForTab = {
                    showUndoSnackbarForTabInvoked = true
                },
                navigateToHomeAndDeleteSession = {
                    navigateToHomeAndDeleteSessionInvoked = true
                },
            ),
        )

        controller.handleTabSelected(currentTab, "source")
        controller.handleTabDeletion("privateTab")

        assertTrue(showUndoSnackbarForTabInvoked)
        assertFalse(navigateToHomeAndDeleteSessionInvoked)
    }

    private fun createController(
        navigateToHomeAndDeleteSession: (String) -> Unit = { },
        selectTabPosition: (Int, Boolean) -> Unit = { _, _ -> },
        dismissTray: () -> Unit = { },
        showUndoSnackbarForTab: (Boolean) -> Unit = { _ -> },
        showCancelledDownloadWarning: (Int, String?, String?) -> Unit = { _, _, _ -> },
    ): DefaultTabsTrayController {
        return DefaultTabsTrayController(
            activity = activity,
            appStore = appStore,
            tabsTrayStore = trayStore,
            browserStore = browserStore,
            settings = settings,
            browsingModeManager = browsingModeManager,
            navController = navController,
            navigateToHomeAndDeleteSession = navigateToHomeAndDeleteSession,
            profiler = profiler,
            navigationInteractor = navigationInteractor,
            tabsUseCases = tabsUseCases,
            selectTabPosition = selectTabPosition,
            dismissTray = dismissTray,
            showUndoSnackbarForTab = showUndoSnackbarForTab,
            showCancelledDownloadWarning = showCancelledDownloadWarning,
        )
    }
}
