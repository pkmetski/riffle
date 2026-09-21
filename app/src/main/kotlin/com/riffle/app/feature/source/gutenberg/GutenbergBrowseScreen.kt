package com.riffle.app.feature.source.gutenberg

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.annotations.AnnotationsListScreen
import com.riffle.app.feature.source.common.rememberDrawerButtonGestureExclusion
import com.riffle.app.feature.source.websource.WebSourceHomeTab
import com.riffle.app.feature.source.websource.WebSourceToReadTab
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.core.models.SourceType
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.library.AnnotationsListViewModel
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.source.ui.SourceBrowseHeader
import com.riffle.feature.source.ui.SourceTypeIcon
import com.riffle.feature.source.ui.websource.GutenbergBrowseViewModel
import com.riffle.feature.source.ui.websource.UnboundedBrowseLibraryTabFor
import com.riffle.feature.source.ui.websource.UnboundedCoverGridZoomProvider
import org.koin.androidx.compose.koinViewModel

/**
 * Gutenberg Source screen. Distinct route ("gutenberg_browse/{libraryId}/{name}") from
 * LibraryItemsScreen — Gutenberg has no ABS-shape library mirror, so we can't reuse that
 * screen's refresh/capability plumbing. Instead we host a small tab bar with four surfaces
 * that ARE consistent with every other Source (Home / To Read / Annotations / Library),
 * mirroring the Chitanka browse screen's structure.
 *
 * The Library tab is `feature:source-ui`'s [UnboundedBrowseLibraryTabFor] — the same composable
 * the iOS browse screen renders. Only this four-tab shell is Android-specific.
 */
@Composable
fun GutenbergBrowseScreen(
    libraryName: String,
    windowSizeClass: WindowSizeClass,
    onOpenDrawer: () -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    onOpenDetail: (itemId: String) -> Unit,
    onAnnotatedBookClick: (sourceId: String, itemId: String) -> Unit,
    viewModel: GutenbergBrowseViewModel = koinViewModel(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    val query by viewModel.query.collectAsState()
    val persistedCoverScale by viewModel.coverGridScale.collectAsState()

    val visibility by koinViewModel<com.riffle.app.feature.library.LibraryTabVisibilityViewModel>()
        .visibility.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.openDetailEvents.collect { event -> onOpenDetail(event.itemId) }
    }

    // Clamp if a rememberSaveable-restored selectedTab lands on a tab that is currently hidden
    // (empty To Read or Annotations list). Matches the LibraryTabBar clamp on the ABS/Komga side.
    LaunchedEffect(visibility.toRead, visibility.annotations) {
        val hidden = when (selectedTab) {
            TAB_TO_READ -> !visibility.toRead
            TAB_ANNOTATIONS -> !visibility.annotations
            else -> false
        }
        if (hidden) selectedTab = TAB_HOME
    }

    // Switch to the Library tab automatically when the user starts typing in the always-visible
    // search field, regardless of which tab they are currently on.
    LaunchedEffect(query) {
        if (query.isNotEmpty()) selectedTab = TAB_LIBRARY
    }

    val drawerButtonModifier = rememberDrawerButtonGestureExclusion()
    Scaffold(
        topBar = {
            SourceBrowseHeader(
                sourceName = libraryName,
                searchQuery = query,
                onSearchQueryChange = viewModel::onQueryChange,
                onOpenDrawer = onOpenDrawer,
                sourceIcon = {
                    SourceTypeIcon(type = SourceType.GUTENBERG, size = 24.dp, modifier = Modifier.padding(end = 8.dp))
                },
                drawerButtonModifier = drawerButtonModifier,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == TAB_HOME,
                    onClick = { selectedTab = TAB_HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_home)) },
                )
                if (visibility.toRead) {
                    NavigationBarItem(
                        selected = selectedTab == TAB_TO_READ,
                        onClick = { selectedTab = TAB_TO_READ },
                        icon = { Icon(RiffleIcons.ToReadFilled, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_to_read)) },
                    )
                }
                if (visibility.annotations) {
                    NavigationBarItem(
                        selected = selectedTab == TAB_ANNOTATIONS,
                        onClick = { selectedTab = TAB_ANNOTATIONS },
                        icon = { Icon(RiffleIcons.Annotations, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_annotations)) },
                    )
                }
                NavigationBarItem(
                    selected = selectedTab == TAB_LIBRARY,
                    onClick = { selectedTab = TAB_LIBRARY },
                    icon = { Icon(Icons.Filled.GridView, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_all_books)) },
                )
            }
        },
    ) { padding ->
        TabletContentWidthContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            UnboundedCoverGridZoomProvider(
                persistedScale = persistedCoverScale,
                onPersistScaleChange = viewModel::setCoverGridScale,
            ) { onCoverScaleChange ->
                when (selectedTab) {
                    TAB_HOME -> WebSourceHomeTab(
                        onOpenDetail = onOpenDetail,
                        onSectionSeeMore = onSectionSeeMore,
                        onCoverScaleChange = onCoverScaleChange,
                    )
                    TAB_TO_READ -> WebSourceToReadTab(
                        onOpenDetail = onOpenDetail,
                        onCoverScaleChange = onCoverScaleChange,
                    )
                    TAB_ANNOTATIONS ->
                        GutenbergAnnotationsTab(onAnnotatedBookClick = onAnnotatedBookClick)
                    TAB_LIBRARY -> UnboundedBrowseLibraryTabFor(
                        sourceType = SourceType.GUTENBERG,
                        viewModel = viewModel,
                        onCoverScaleChange = onCoverScaleChange,
                        isAudio = false,
                    )
                }
            }
        }
    }
}

private const val TAB_HOME = 0
private const val TAB_TO_READ = 1
private const val TAB_ANNOTATIONS = 2
private const val TAB_LIBRARY = 3

@Composable
private fun GutenbergAnnotationsTab(
    onAnnotatedBookClick: (sourceId: String, itemId: String) -> Unit,
    viewModel: AnnotationsListViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    AnnotationsListScreen(
        state = state,
        token = viewModel.authToken,
        onBookClick = onAnnotatedBookClick,
    )
}
