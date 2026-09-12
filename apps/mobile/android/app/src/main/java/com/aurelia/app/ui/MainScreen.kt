package com.aurelia.app.ui

import android.content.pm.ApplicationInfo
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.aurelia.app.audio.AudioManager
import com.aurelia.app.audio.VisualizerStyle
import com.aurelia.app.player.PlayerController
import com.aurelia.app.storage.SessionStore
import com.aurelia.app.ui.components.AnimatedPlayPauseIcon
import com.aurelia.app.ui.components.AudioVisualizer
import com.aurelia.app.ui.components.BottomBarDimensions.MiniPlayerHeight
import com.aurelia.app.ui.components.BottomBarDimensions.NavBarContentHeight
import com.aurelia.app.ui.components.VisualizerFrameMetrics
import com.aurelia.app.ui.navigation.Screen
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal enum class MainTab(
  val screen: Screen,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
) {
  Home(Screen.Home, "Home", Icons.Filled.Home, Icons.Outlined.Home),
  Search(Screen.Search, "Search", Icons.Filled.Search, Icons.Outlined.Search),
  Library(Screen.Library, "Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
}

@Composable
fun MainScreen(
  sessionStore: SessionStore,
  playerController: PlayerController,
  onLogout: () -> Unit,
  onSessionSwitched: () -> Unit,
) {
  val navController = rememberNavController()
  var activeTab by rememberSaveable { mutableStateOf(MainTab.Home) }

  // HomeViewModel hoisted here to survive tab switches
  val homeViewModel: HomeViewModel =
    viewModel(
      factory = remember { viewModelFactory { HomeViewModel(sessionStore, playerController) } },
    )

  // SettingsViewModel hoisted here to survive tab switches
  val settingsViewModel: SettingsViewModel =
    viewModel(
      factory = remember { viewModelFactory { SettingsViewModel(sessionStore) } },
    )

  // PlaylistViewModel hoisted here to survive tab switches
  val playlistViewModel: PlaylistViewModel =
    viewModel(
      factory = remember { viewModelFactory { PlaylistViewModel(sessionStore, playerController) } },
    )

  val libraryViewModel: LibraryViewModel =
    viewModel(
      factory = remember { viewModelFactory { LibraryViewModel(sessionStore, playerController) } },
    )
  val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()

  // Session stores reuse loaded data and in-flight requests when this UI is recreated.
  LaunchedEffect(homeViewModel, libraryViewModel, playlistViewModel) {
    homeViewModel.ensureLoaded()
    libraryViewModel.ensureLoaded()
    playlistViewModel.ensureLoaded()
  }

  // --- NAVIGATION HELPERS ---
  fun navigateToTab(screen: Screen) {
    navController.navigate(screen) {
      // Pop up to the start destination of the graph to avoid building up a large stack of tabs
      popUpTo(navController.graph.findStartDestination().id) {
        saveState = true
      }
      // Avoid multiple copies of the same destination
      launchSingleTop = true
      // Restore state when reselecting a previously selected item
      restoreState = true
    }
  }

  @Suppress("UnusedBoxWithConstraintsScope")
  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val screenHeightPx = constraints.maxHeight.toFloat()
    val miniPlayerTopMargin = 4.dp
    val expandFractionThreshold = 0.35f
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarHeightPx = with(density) { (NavBarContentHeight + bottomInset).toPx() }
    val miniPlayerHeightPx = with(density) { MiniPlayerHeight.toPx() }
    val miniPlayerTopMarginPx = with(density) { miniPlayerTopMargin.toPx() }
    val collapsedSheetY =
      (screenHeightPx - miniPlayerHeightPx - navBarHeightPx - miniPlayerTopMarginPx)
        .coerceAtLeast(0f)
    val playerDragOffset = remember { createPlayerSheetOffset(collapsedSheetY) }
    val dragProgress =
      if (collapsedSheetY > 0f) {
        ((collapsedSheetY - playerDragOffset.value) / collapsedSheetY).coerceIn(0f, 1f)
      } else {
        1f
      }
    val sheetCornerRadius = lerp(20.dp, 0.dp, dragProgress)
    val sheetHorizontalPadding = lerp(12.dp, 0.dp, dragProgress)
    val sheetHeightPx = miniPlayerHeightPx + (screenHeightPx - miniPlayerHeightPx) * dragProgress
    val sheetShape =
      with(density) {
        PlayerSheetShape(
          top = playerDragOffset.value,
          height = sheetHeightPx,
          horizontalInset = sheetHorizontalPadding.toPx(),
          topRadius = sheetCornerRadius.toPx(),
          bottomRadius = sheetCornerRadius.toPx(),
        )
      }
    val fullPlayerVisible = dragProgress >= 0.55f
    val playerTransitionState = remember { SeekableTransitionState(false) }
    val playerTransition = rememberTransition(playerTransitionState, label = "mini-to-full-player")

    LaunchedEffect(dragProgress) {
      when {
        dragProgress <= 0f -> playerTransitionState.snapTo(false)
        dragProgress >= 1f -> playerTransitionState.snapTo(true)
        else -> {
          val seek = playerTransitionSeek(dragProgress, playerTransitionState.currentState)
          playerTransitionState.seekTo(
            fraction = seek.fraction,
            targetState = seek.targetExpanded,
          )
        }
      }
    }

    // Keep collapsed position in sync when layout changes (e.g., initial measurement)
    LaunchedEffect(collapsedSheetY) {
      playerDragOffset.updateBounds(lowerBound = 0f, upperBound = collapsedSheetY)
      // Only snap if player is collapsed - don't interrupt drag/expand animations
      if (dragProgress < 0.1f) {
        playerDragOffset.snapTo(collapsedSheetY)
      }
    }

    fun openPlayerAnimated(initialVelocity: Float = 0f) {
      scope.launch {
        playerDragOffset.animateTo(
          targetValue = 0f,
          initialVelocity = initialVelocity,
          animationSpec =
            spring(
              dampingRatio = Spring.DampingRatioNoBouncy,
              stiffness = Spring.StiffnessMediumLow,
            ),
        )
      }
    }

    fun closePlayer(initialVelocity: Float = 0f) {
      scope.launch {
        playerDragOffset.animateTo(
          targetValue = collapsedSheetY,
          initialVelocity = initialVelocity,
          animationSpec =
            spring(
              dampingRatio = Spring.DampingRatioNoBouncy,
              stiffness = Spring.StiffnessMediumLow,
            ),
        )
      }
    }

    fun animatePlayerOffset(
      target: Float,
      initialVelocity: Float = 0f,
    ) {
      scope.launch {
        playerDragOffset.animateTo(
          targetValue = target,
          initialVelocity = initialVelocity,
          animationSpec =
            spring(
              dampingRatio = Spring.DampingRatioNoBouncy,
              stiffness = Spring.StiffnessMediumLow,
            ),
        )
      }
    }

    fun onPlayerDrag(delta: Float) {
      scope.launch {
        playerDragOffset.snapTo((playerDragOffset.value + delta).coerceIn(0f, collapsedSheetY))
      }
    }

    fun onPlayerDragEnd(velocity: Float) {
      val shouldOpen =
        when {
          velocity < -80f -> true
          velocity > 120f -> false
          else -> dragProgress > expandFractionThreshold
        }
      if (shouldOpen) {
        openPlayerAnimated(initialVelocity = velocity)
      } else {
        animatePlayerOffset(collapsedSheetY, initialVelocity = velocity)
      }
    }

    // 1. SCAFFOLD / CONTENT AREA
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background),
    ) {
      NavHost(
        navController = navController,
        startDestination = Screen.Home,
        // SETTINGS-STYLE ANIMATIONS
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(400)) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(400)) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(400)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(400)) },
      ) {
        // --- TABS ---
        composable<Screen.Home> {
          HomeScreen(
            viewModel = homeViewModel,
            sessionStore = sessionStore,
            playerController = playerController,
            playlistViewModel = playlistViewModel,
            onOpenPlayer = { openPlayerAnimated() },
            onOpenSettings = { navController.navigate(Screen.Settings) },
            onNavigateToAlbum = { navController.navigate(it) },
            onNavigateToArtist = { navController.navigate(it) },
            hasPlayerBar = libraryState.nowPlaying != null,
          )
        }

        composable<Screen.Library> {
          LibraryOverviewScreen(
            hasPlayerBar = libraryState.nowPlaying != null,
            onNavigate = { navController.navigate(it) },
            onOpenSettings = { navController.navigate(Screen.Settings) },
          )
        }

        composable<Screen.Songs> {
          LibraryScreen(
            libraryViewModel = libraryViewModel,
            sessionStore = sessionStore,
            playerController = playerController,
            playlistViewModel = playlistViewModel,
            onOpenPlayer = { openPlayerAnimated() },
            onNavigateToAlbum = { navController.navigate(it) },
            onNavigateToArtist = { navController.navigate(it) },
            hasPlayerBar = libraryState.nowPlaying != null,
          )
        }

        composable<Screen.Albums> {
          AlbumsScreen(
            libraryViewModel = libraryViewModel,
            onNavigateToAlbum = { navController.navigate(it) },
            hasPlayerBar = libraryState.nowPlaying != null,
          )
        }

        composable<Screen.Artists> {
          ArtistsScreen(
            libraryViewModel = libraryViewModel,
            sessionStore = sessionStore,
            onNavigateToArtist = { navController.navigate(it) },
            hasPlayerBar = libraryState.nowPlaying != null,
          )
        }

        composable<Screen.Playlists> {
          PlaylistsScreen(
            viewModel = playlistViewModel,
            librarySongs = libraryState.songs,
            onOpenPlayer = { openPlayerAnimated() },
            onNavigateToPlaylist = { navController.navigate(it) },
            hasPlayerBar = libraryState.nowPlaying != null,
          )
        }

        composable<Screen.Search> {
          SearchScreen(
            libraryViewModel = libraryViewModel,
            sessionStore = sessionStore,
            playerController = playerController,
            onOpenPlayer = { openPlayerAnimated() },
            onNavigateToAlbum = { navController.navigate(it) },
            onNavigateToArtist = { navController.navigate(it) },
            hasPlayerBar = libraryState.nowPlaying != null,
            playlistViewModel = playlistViewModel,
          )
        }

        composable<Screen.Settings> {
          SettingsScreen(
            sessionStore = sessionStore,
            settingsViewModel = settingsViewModel,
            onLogout = onLogout,
            onSessionSwitched = onSessionSwitched,
            hasPlayerBar = libraryState.nowPlaying != null,
          )
        }

        // --- DETAILS SCREENS ---
        composable<Screen.AlbumDetail> { backStackEntry ->
          val args = backStackEntry.toRoute<Screen.AlbumDetail>()
          AlbumDetailScreen(
            libraryViewModel = libraryViewModel,
            albumId = args.albumId,
            albumName = args.albumName,
            sessionStore = sessionStore,
            playerController = playerController,
            playlistViewModel = playlistViewModel,
            onBack = { navController.popBackStack() },
            onOpenPlayer = { openPlayerAnimated() },
            onNavigateToArtist = { navController.navigate(it) },
            hasPlayerBar = libraryState.nowPlaying != null,
          )
        }

        composable<Screen.ArtistDetail> { backStackEntry ->
          val args = backStackEntry.toRoute<Screen.ArtistDetail>()
          ArtistDetailScreen(
            libraryViewModel = libraryViewModel,
            artistId = args.artistId,
            artistName = args.artistName,
            sessionStore = sessionStore,
            playerController = playerController,
            playlistViewModel = playlistViewModel,
            onBack = { navController.popBackStack() },
            onOpenPlayer = { openPlayerAnimated() },
            onNavigateToAlbum = { navController.navigate(it) },
            hasPlayerBar = libraryState.nowPlaying != null,
          )
        }

        composable<Screen.PlaylistDetail> { backStackEntry ->
          val args = backStackEntry.toRoute<Screen.PlaylistDetail>()
          PlaylistDetailScreen(
            playlistId = args.playlistId,
            playlistName = args.playlistName,
            viewModel = playlistViewModel,
            onBack = { navController.popBackStack() },
            onOpenPlayer = { openPlayerAnimated() },
            hasPlayerBar = libraryState.nowPlaying != null,
          )
        }
      }

      // 2. BOTTOM NAV BAR OVERLAY
      val navBackStackEntry by navController.currentBackStackEntryAsState()
      val currentDestination = navBackStackEntry?.destination
      val selectedTab =
        when {
          currentDestination?.hasRoute<Screen.Home>() == true -> MainTab.Home
          currentDestination?.hasRoute<Screen.Search>() == true -> MainTab.Search
          currentDestination?.hasRoute<Screen.Library>() == true ||
            currentDestination?.hasRoute<Screen.Songs>() == true ||
            currentDestination?.hasRoute<Screen.Albums>() == true ||
            currentDestination?.hasRoute<Screen.Artists>() == true ||
            currentDestination?.hasRoute<Screen.Playlists>() == true -> MainTab.Library
          // Details and settings retain the tab from which they were opened.
          else -> activeTab
        }
      LaunchedEffect(selectedTab) { activeTab = selectedTab }

      // With a player, start the fade at its top edge; otherwise finish it at navigation.
      val dockColor = MaterialTheme.colorScheme.background
      val hasPlayer = libraryState.nowPlaying != null
      val dockFadeHeight = 24.dp
      Column(
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth(),
      ) {
        Spacer(
          Modifier
            .fillMaxWidth()
            .height(if (hasPlayer) MiniPlayerHeight + miniPlayerTopMargin else dockFadeHeight)
            .background(
              Brush.verticalGradient(
                colors = listOf(dockColor.copy(alpha = 0f), dockColor),
                endY = with(density) { dockFadeHeight.toPx() },
              ),
            ).then(
              // Covered page content behind the player must not receive taps.
              if (hasPlayer) Modifier.pointerInput(Unit) { detectTapGestures {} } else Modifier,
            ),
        )
        BottomNavBar(
          selectedTab = selectedTab,
          onNavigate = { navigateToTab(it.screen) },
        )
      }
    }

    // 3. MINIPLAYER OVERLAY
    if (libraryState.nowPlaying != null) {
      val playerViewModel: PlayerViewModel =
        viewModel(factory = viewModelFactory { PlayerViewModel(playerController, sessionStore) })
      val playerState by playerViewModel.state.collectAsStateWithLifecycle()
      val savedPlayerUi = rememberSaveableStateHolder()
      val context = LocalContext.current
      val transitionsEnabled =
        remember(context, sessionStore) {
          !(
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
              sessionStore.getDebugDisablePlayerTransitions()
          )
        }
      // Handle predictive back gesture when player is expanded
      PredictiveBackHandler(enabled = dragProgress > 0.5f) { progress ->
        try {
          progress.collect { backEvent ->
            // Map back gesture progress (0-1) to player offset
            val targetOffset = collapsedSheetY * backEvent.progress
            playerDragOffset.snapTo(targetOffset)
          }
          // Gesture completed - close player
          closePlayer()
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
          // Gesture cancelled - snap back to open
          openPlayerAnimated()
        }
      }

      val playerSurfaceColor = MaterialTheme.colorScheme.primaryContainer
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .graphicsLayer {
              clip = true
              shape = sheetShape
            }.background(
              color = playerSurfaceColor,
              shape = sheetShape,
            ).zIndex(1f),
      ) {
        val nowPlaying = libraryState.nowPlaying ?: return@Box
        val sharedContentKey =
          libraryState.currentSongId ?: "${nowPlaying.title}|${nowPlaying.artist}"

        val morph =
          rememberPlayerMorph(
            dragProgress,
            nowPlaying.albumArtUrl,
            collapsedSheetY + miniPlayerHeightPx,
            playerState.showLyrics,
            transitionsEnabled,
            nowPlaying.albumId ?: libraryState.currentSongId,
            sessionStore,
          )
        CompositionLocalProvider(LocalPlayerMorph provides morph) {
          PlayerBackdrop(
            albumArtUrl = nowPlaying.albumArtUrl,
            sessionStore = sessionStore,
            modifier = Modifier.fillMaxSize().alpha(dragProgress),
            animateArtwork = dragProgress > 0f,
          )
          PlayerArtworkVideoSource()
          SharedTransitionLayout {
            playerTransition.AnimatedContent(
              modifier = Modifier.fillMaxSize(),
              transitionSpec = { playerContentTransform() },
              contentKey = { expanded -> expanded },
            ) { expanded ->
              if (expanded) {
                Box(
                  modifier =
                    Modifier
                      .fillMaxSize()
                      .pointerInput(Unit) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                          onVerticalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                            onPlayerDrag(dragAmount)
                          },
                          onDragEnd = {
                            val velocity = if (totalDrag != 0f) totalDrag * 12f else 0f
                            onPlayerDragEnd(velocity)
                            totalDrag = 0f
                          },
                        )
                      },
                ) {
                  savedPlayerUi.SaveableStateProvider("full-player") {
                    PlayerScreen(
                      viewModel = playerViewModel,
                      sessionStore = sessionStore,
                      onBack = { closePlayer() },
                      modifier = Modifier.fillMaxSize(),
                      isVisible = fullPlayerVisible,
                      onNavigateToAlbum = {
                        closePlayer()
                        navController.navigate(it)
                      },
                      onNavigateToArtist = {
                        closePlayer()
                        navController.navigate(it)
                      },
                      sharedTransitionScope = this@SharedTransitionLayout,
                      animatedVisibilityScope = this@AnimatedContent,
                      sharedContentKey = sharedContentKey,
                    )
                  }
                }
              } else {
                // Shared elements must start at their resting screen positions, even
                // while the sheet behind them expands. Neither endpoint moves.
                Box(
                  Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                ) {
                  MiniPlayerBar(
                    modifier = Modifier.offset { IntOffset(0, collapsedSheetY.roundToInt()) },
                    title = nowPlaying.title,
                    artist = nowPlaying.artist,
                    isPlaying = nowPlaying.isPlaying,
                    isBuffering = nowPlaying.isBuffering,
                    hasPrevious = nowPlaying.hasPrevious,
                    hasNext = nowPlaying.hasNext,
                    onPrevious = { libraryViewModel.skipPrevious() },
                    onPlayPause = { libraryViewModel.togglePlayPause() },
                    onNext = { libraryViewModel.skipNext() },
                    onClick = { openPlayerAnimated() },
                    onDrag = { delta -> onPlayerDrag(delta) },
                    onDragEnd = { velocity -> onPlayerDragEnd(velocity) },
                    albumId = nowPlaying.albumId,
                    artistId = nowPlaying.artistId,
                    albumName = nowPlaying.albumName,
                    onNavigateToAlbum = { navController.navigate(it) },
                    onNavigateToArtist = { navController.navigate(it) },
                    sessionStore = sessionStore,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@AnimatedContent,
                    sharedContentKey = sharedContentKey,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

/** Navigation blends into the app; only the player floats above it. */
@Composable
internal fun BottomNavBar(
  selectedTab: MainTab,
  onNavigate: (MainTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
  Column(
    modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.background)
      .padding(bottom = bottomInset),
  ) {
    Row(
      Modifier.fillMaxWidth().height(NavBarContentHeight).padding(horizontal = 20.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      MainTab.entries.forEach { item ->
        val selected = selectedTab == item
        BottomNavItem(
          label = item.label,
          icon = if (selected) item.selectedIcon else item.unselectedIcon,
          selected = selected,
          onClick = { onNavigate(item) },
        )
      }
    }
  }
}

@Composable
private fun RowScope.BottomNavItem(
  label: String,
  icon: ImageVector,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val iconTint by animateColorAsState(
    targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
    animationSpec = tween(durationMillis = 200),
  )
  val textColor by animateColorAsState(
    targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
    animationSpec = tween(durationMillis = 200),
  )

  Column(
    modifier =
      Modifier
        .weight(1f)
        .semantics { this.selected = selected }
        .clip(RoundedCornerShape(20.dp))
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          role = Role.Tab,
          onClick = onClick,
        ).padding(vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      modifier =
        Modifier
          .size(width = 64.dp, height = 36.dp)
          .clip(RoundedCornerShape(20.dp))
          .background(colors.primary.copy(alpha = if (selected) 0.2f else 0f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = iconTint,
        modifier = Modifier.size(20.dp),
      )
    }

    Spacer(modifier = Modifier.height(6.dp))

    ProvideTextStyle(MaterialTheme.typography.labelSmall) {
      Text(
        text = label,
        color = textColor,
        maxLines = 1,
      )
    }
  }
}

@Composable
fun MiniPlayerBar(
  title: String,
  artist: String,
  isPlaying: Boolean,
  isBuffering: Boolean,
  hasPrevious: Boolean,
  hasNext: Boolean,
  onPrevious: () -> Unit,
  onPlayPause: () -> Unit,
  onNext: () -> Unit,
  onClick: () -> Unit,
  onDrag: (Float) -> Unit,
  onDragEnd: (Float) -> Unit,
  modifier: Modifier = Modifier,
  albumId: String? = null,
  artistId: String? = null,
  albumName: String? = null,
  onNavigateToAlbum: ((Screen.AlbumDetail) -> Unit)? = null,
  onNavigateToArtist: ((Screen.ArtistDetail) -> Unit)? = null,
  sessionStore: SessionStore? = null,
  sharedTransitionScope: SharedTransitionScope? = null,
  animatedVisibilityScope: AnimatedVisibilityScope? = null,
  sharedContentKey: String? = null,
) {
  val colors = MaterialTheme.colorScheme
  val morph = LocalPlayerMorph.current
  val buttonPrimary = morph?.primary ?: colors.primary

  val visualizerState by AudioManager.visualizerState.collectAsStateWithLifecycle()
  val visualizerEnabled = remember(sessionStore) { sessionStore?.getVisualizerEnabled() ?: false }
  val visualizerStyleName = remember(sessionStore) { sessionStore?.getVisualizerStyle() ?: "BARS" }
  val visualizerStyle =
    remember(visualizerStyleName) {
      try {
        VisualizerStyle.valueOf(visualizerStyleName)
      } catch (_: Exception) {
        VisualizerStyle.BARS
      }
    }
  val shouldShowVisualizer =
    visualizerEnabled && visualizerState.enabled && isPlaying && visualizerState.frequencyData.isNotEmpty()

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .pointerInput(Unit) {
          var netDrag = 0f
          detectVerticalDragGestures(
            onVerticalDrag = { _, dragAmount ->
              netDrag += dragAmount
              // Only apply upward drags visually
              if (dragAmount < 0f) {
                onDrag(dragAmount)
              }
            },
            onDragEnd = {
              // Use net displacement for velocity - if user dragged back down, this cancels out
              val velocity = if (netDrag < 0f) netDrag * 12f else 0f
              onDragEnd(velocity)
              netDrag = 0f
            },
          )
        }.height(MiniPlayerHeight),
  ) {
    AnimatedVisibility(
      visible = shouldShowVisualizer,
      enter = fadeIn(animationSpec = tween(250)),
      exit = fadeOut(animationSpec = tween(250)),
    ) {
      AudioVisualizer(
        frequencyData = visualizerState.frequencyData,
        timeDomainData = visualizerState.waveform,
        style = visualizerStyle,
        accentColor = colors.primary,
        modifier =
          Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 0.25f },
        boost = 0.82f,
      )
    }
    VisualizerFrameMetrics(tag = "MiniPlayerVisualizer", enabled = shouldShowVisualizer)

    Row(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(horizontal = 18.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Clickable area for opening player (album art + text)
      Row(
        modifier =
          Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PlayerArtwork(
          modifier =
            Modifier
              .playerSharedElement(
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                key = playerSharedKey(sharedContentKey, "artwork"),
              ).graphicsLayer { alpha = 1f - (morph?.lyricsAlpha ?: 0f) }
              .size(44.dp)
              .clickable(
                enabled = !albumId.isNullOrBlank(),
                onClick = {
                  albumId?.let { id ->
                    onNavigateToAlbum?.invoke(Screen.AlbumDetail(id, albumName ?: "Unknown Album"))
                  }
                },
              ),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            text = title,
            style =
              MaterialTheme.typography.titleSmall.copy(
                fontSize = 15.sp,
                letterSpacing = (-0.2).sp,
              ),
            fontWeight = FontWeight.Medium,
            color = colors.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier.playerSharedBounds(
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                key = playerSharedKey(sharedContentKey, "title"),
              ),
          )
          Text(
            text = artist,
            style =
              MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp,
              ),
            color = colors.onPrimaryContainer.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier
                .playerSharedBounds(
                  sharedTransitionScope = sharedTransitionScope,
                  animatedVisibilityScope = animatedVisibilityScope,
                  key = playerSharedKey(sharedContentKey, "artist"),
                ).clickable(
                  enabled = !artistId.isNullOrBlank(),
                  onClick = {
                    artistId?.let { id ->
                      onNavigateToArtist?.invoke(Screen.ArtistDetail(id, artist))
                    }
                  },
                ),
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Box(
        modifier =
          Modifier
            .playerSharedElement(
              sharedTransitionScope = sharedTransitionScope,
              animatedVisibilityScope = animatedVisibilityScope,
              key = playerSharedKey(sharedContentKey, "previous"),
            ).size(36.dp)
            .clip(CircleShape)
            .background(
              morph?.skipBackground(hasPrevious) ?: colors.primary.copy(alpha = if (hasPrevious) 0.2f else 0.08f),
            ).then(
              if (hasPrevious) {
                Modifier.clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
                  onClick = onPrevious,
                )
              } else {
                Modifier
              },
            ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.SkipPrevious,
          contentDescription = "Previous",
          tint = buttonPrimary.copy(alpha = if (hasPrevious) 1f else 0.4f),
          modifier = Modifier.size(lerp(22.dp, 32.dp, LocalPlayerMorph.current?.expansion ?: 0f)),
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      Box(
        modifier =
          Modifier
            .playerSharedElement(
              sharedTransitionScope = sharedTransitionScope,
              animatedVisibilityScope = animatedVisibilityScope,
              key = playerSharedKey(sharedContentKey, "play-pause"),
            ).size(36.dp)
            .clip(playerPlayButtonShape(isPlaying, isBuffering, fallbackExpansion = 0f))
            .background(buttonPrimary)
            .then(
              if (!isBuffering) {
                Modifier.clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
                  onClick = onPlayPause,
                )
              } else {
                Modifier
              },
            ),
        contentAlignment = Alignment.Center,
      ) {
        if (isBuffering) {
          CircularProgressIndicator(
            modifier = Modifier.size(lerp(18.dp, 32.dp, morph?.expansion ?: 0f)),
            color = morph?.onPrimary ?: colors.onPrimary,
            strokeWidth = 2.dp,
          )
        } else {
          AnimatedPlayPauseIcon(
            isPlaying = isPlaying,
            tint = morph?.onPrimary ?: colors.onPrimary,
            modifier = Modifier.size(lerp(20.dp, 36.dp, morph?.expansion ?: 0f)),
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Box(
        modifier =
          Modifier
            .playerSharedElement(
              sharedTransitionScope = sharedTransitionScope,
              animatedVisibilityScope = animatedVisibilityScope,
              key = playerSharedKey(sharedContentKey, "next"),
            ).size(36.dp)
            .clip(CircleShape)
            .background(morph?.skipBackground(hasNext) ?: colors.primary.copy(alpha = if (hasNext) 0.2f else 0.08f))
            .then(
              if (hasNext) {
                Modifier.clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
                  onClick = onNext,
                )
              } else {
                Modifier
              },
            ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.SkipNext,
          contentDescription = "Next",
          tint = buttonPrimary.copy(alpha = if (hasNext) 1f else 0.4f),
          modifier = Modifier.size(lerp(22.dp, 32.dp, LocalPlayerMorph.current?.expansion ?: 0f)),
        )
      }
    }
  }
}
