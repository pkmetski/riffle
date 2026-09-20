package com.riffle.app.feature.source.radioes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import com.riffle.feature.source.ui.SourceBrowseHeader
import com.riffle.feature.source.ui.SourceTypeIcon
import com.riffle.core.models.SourceType
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import com.riffle.app.R
import com.riffle.feature.library.LibrarySectionType
import com.riffle.app.feature.library.LocalCoversAreSquare
import com.riffle.app.feature.source.common.rememberDrawerButtonGestureExclusion
import com.riffle.feature.source.ui.websource.RadioEsBrowseViewModel
import com.riffle.feature.source.ui.websource.UnboundedBrowseLibraryTabFor
import com.riffle.feature.source.ui.websource.UnboundedCoverGridZoomProvider
import com.riffle.app.feature.source.websource.WebSourceHomeTab
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.app.feature.source.websource.WebSourceToReadTab
import com.riffle.app.ui.theme.RiffleIcons

@Composable
fun RadioEsBrowseScreen(
    libraryName: String,
    windowSizeClass: WindowSizeClass,
    onOpenDrawer: () -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    onOpenDetail: (itemId: String) -> Unit,
    onAnnotatedBookClick: (sourceId: String, itemId: String) -> Unit,
    viewModel: RadioEsBrowseViewModel = koinViewModel(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_HOME) }

    LaunchedEffect(viewModel) {
        viewModel.openDetailEvents.collect { event -> onOpenDetail(event.itemId) }
    }

    val query by viewModel.query.collectAsState()
    val persistedCoverScale by viewModel.coverGridScale.collectAsState()

    val visibility by koinViewModel<com.riffle.app.feature.library.LibraryTabVisibilityViewModel>()
        .visibility.collectAsState()

    LaunchedEffect(visibility.toRead) {
        if (selectedTab == TAB_TO_READ && !visibility.toRead) selectedTab = TAB_HOME
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
                    SourceTypeIcon(type = SourceType.RADIO_ES, size = 24.dp, modifier = Modifier.padding(end = 8.dp))
                },
                drawerButtonModifier = drawerButtonModifier,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == TAB_HOME,
                    onClick = { selectedTab = TAB_HOME },
                    icon = { Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.ui_home)) },
                )
                if (visibility.toRead) {
                    NavigationBarItem(
                        selected = selectedTab == TAB_TO_READ,
                        onClick = { selectedTab = TAB_TO_READ },
                        icon = { Icon(RiffleIcons.ToReadFilled, contentDescription = stringResource(R.string.ui_to_read)) },
                    )
                }
                NavigationBarItem(
                    selected = selectedTab == TAB_LIBRARY,
                    onClick = { selectedTab = TAB_LIBRARY },
                    icon = { Icon(Icons.Filled.GridView, contentDescription = stringResource(R.string.ui_all_books)) },
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
                CompositionLocalProvider(LocalCoversAreSquare provides true) {
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
                        TAB_LIBRARY -> UnboundedBrowseLibraryTabFor(
                            sourceType = SourceType.RADIO_ES,
                            viewModel = viewModel,
                            onCoverScaleChange = onCoverScaleChange,
                            isAudio = true,
                        )
                    }
                }
            }
        }
    }
}

private const val TAB_HOME = 0
private const val TAB_TO_READ = 1
private const val TAB_LIBRARY = 2

