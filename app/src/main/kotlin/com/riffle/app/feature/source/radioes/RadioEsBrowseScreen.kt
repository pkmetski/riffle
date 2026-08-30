package com.riffle.app.feature.source.radioes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.riffle.app.R
import com.riffle.core.catalog.CatalogFacet
import com.riffle.app.feature.library.LibrarySectionType
import com.riffle.app.feature.library.LocalCoversAreSquare
import com.riffle.app.feature.source.common.toggleSearchOpen
import com.riffle.app.feature.source.websource.UnboundedCatalogGrid
import com.riffle.app.feature.source.websource.UnboundedCoverGridZoomProvider
import com.riffle.app.feature.source.websource.WebSourceCatalogItemCard
import com.riffle.app.feature.source.websource.WebSourceHomeTab
import com.riffle.app.feature.source.websource.WebSourceToReadTab
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.app.ui.theme.RiffleIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioEsBrowseScreen(
    libraryName: String,
    windowSizeClass: WindowSizeClass,
    onOpenDrawer: () -> Unit,
    onSectionSeeMore: (LibrarySectionType) -> Unit,
    onOpenDetail: (itemId: String) -> Unit,
    onAnnotatedBookClick: (sourceId: String, itemId: String) -> Unit,
    viewModel: RadioEsBrowseViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_HOME) }

    LaunchedEffect(viewModel) {
        viewModel.openDetailEvents.collect { event -> onOpenDetail(event.itemId) }
    }

    var searchOpen by remember { mutableStateOf(false) }
    val persistedCoverScale by viewModel.coverGridScale.collectAsState()

    val visibility by hiltViewModel<com.riffle.app.feature.library.LibraryTabVisibilityViewModel>()
        .visibility.collectAsState()

    LaunchedEffect(visibility.toRead) {
        if (selectedTab == TAB_TO_READ && !visibility.toRead) selectedTab = TAB_HOME
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(libraryName) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.ui_open_drawer))
                    }
                },
                actions = {
                    if (selectedTab == TAB_LIBRARY) {
                        IconButton(onClick = {
                            searchOpen = toggleSearchOpen(searchOpen) {
                                viewModel.onQueryChange("")
                            }
                        }) {
                            Icon(
                                if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (searchOpen) "Close search" else "Search",
                            )
                        }
                    }
                },
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
                        TAB_LIBRARY -> RadioEsLibraryTabContent(
                            viewModel = viewModel,
                            searchOpen = searchOpen,
                            onCoverScaleChange = onCoverScaleChange,
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

@Composable
private fun RadioEsLibraryTabContent(
    viewModel: RadioEsBrowseViewModel,
    searchOpen: Boolean,
    onCoverScaleChange: (Float) -> Unit,
) {
    val items by viewModel.filteredItems.collectAsState()
    val notStartedFilterActive by viewModel.notStartedFilterActive.collectAsState()
    val unownedFilterActive by viewModel.unownedFilterActive.collectAsState()
    val hasServerSources by viewModel.hasServerSources.collectAsState()
    val facets by viewModel.facets.collectAsState()
    val selectedFacet by viewModel.selectedFacet.collectAsState()
    val query by viewModel.query.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isPaging by viewModel.isPaging.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text(stringResource(R.string.ui_search_radio_es)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = notStartedFilterActive,
                    onClick = { viewModel.toggleNotStartedFilter() },
                    label = { Text(stringResource(R.string.ui_not_started)) },
                    leadingIcon = if (notStartedFilterActive) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
                )
            }
            if (hasServerSources) {
                item {
                    FilterChip(
                        selected = unownedFilterActive,
                        onClick = { viewModel.toggleUnownedFilter() },
                        label = { Text(stringResource(R.string.ui_unowned)) },
                        leadingIcon = if (unownedFilterActive) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else null,
                    )
                }
            }
            val categoryFacets = radioEsCategoryFacets(facets)
            val languageFacets = radioEsLanguageFacets(facets)
            if (facets.isNotEmpty()) {
                item {
                    FilterChip(
                        selected = selectedFacet == null,
                        onClick = { viewModel.selectFacet(null) },
                        label = { Text(stringResource(R.string.ui_all)) },
                    )
                }
                items(categoryFacets, key = { it.key }) { facet ->
                    FilterChip(
                        selected = selectedFacet == facet.key,
                        onClick = { viewModel.selectFacet(facet.key) },
                        label = { Text(facet.label) },
                    )
                }
                if (languageFacets.isNotEmpty()) {
                    item {
                        RadioEsLanguageFilterChip(
                            languageFacets = languageFacets,
                            selectedFacet = selectedFacet,
                            onSelectFacet = { viewModel.selectFacet(it) },
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && items.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.wrapContentSize().align(Alignment.Center))
                }
                error != null && items.isEmpty() -> {
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                items.isEmpty() -> {
                    Text(
                        if (query.isNotBlank()) {
                            stringResource(R.string.ui_no_results)
                        } else {
                            stringResource(R.string.ui_nothing_to_show)
                        },
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                else -> {
                    UnboundedCatalogGrid(
                        items = items,
                        isPaging = isPaging,
                        hasMore = hasMore,
                        onLoadMore = viewModel::loadMore,
                        onCoverScaleChange = onCoverScaleChange,
                        itemKey = { it.id },
                        coverCellSizeMultiplier = 4f / 3f,
                    ) { item ->
                        WebSourceCatalogItemCard(
                            item = item,
                            isAudio = true,
                            onClick = { viewModel.openDetail(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioEsLanguageFilterChip(
    languageFacets: List<CatalogFacet>,
    selectedFacet: String?,
    onSelectFacet: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLanguage = languageFacets.firstOrNull { it.key == selectedFacet }

    Box {
        FilterChip(
            selected = selectedLanguage != null,
            onClick = { expanded = true },
            label = {
                Text(
                    stringResource(
                        R.string.ui_language_filter,
                        selectedLanguage?.label ?: stringResource(R.string.ui_any),
                    ),
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ui_any)) },
                onClick = {
                    onSelectFacet(null)
                    expanded = false
                },
                leadingIcon = if (selectedLanguage == null) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else null,
            )
            languageFacets.forEach { facet ->
                DropdownMenuItem(
                    text = { Text(facet.label) },
                    onClick = {
                        onSelectFacet(facet.key)
                        expanded = false
                    },
                    leadingIcon = if (facet.key == selectedFacet) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

internal fun radioEsLanguageFacets(facets: List<CatalogFacet>): List<CatalogFacet> =
    facets.filter { it.key.startsWith(RADIO_ES_LANGUAGE_FACET_PREFIX) }

internal fun radioEsCategoryFacets(facets: List<CatalogFacet>): List<CatalogFacet> =
    facets.filterNot { it.key.startsWith(RADIO_ES_LANGUAGE_FACET_PREFIX) }

private const val RADIO_ES_LANGUAGE_FACET_PREFIX = "lang:"
