package com.aurelia.app.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.aurelia_core.Song
import java.io.File
import kotlin.math.abs

data class GemmaRuntimeConfig(
  val modelPath: String?,
  val cacheDir: String?,
)

class GemmaPlaylistGenerator {
  suspend fun generate(
    request: SmartPlaylistRequest,
    candidates: List<CandidateSong>,
    runtimeConfig: GemmaRuntimeConfig,
  ): SmartPlaylistPreview =
    withContext(Dispatchers.IO) {
      require(request.prompt.isNotBlank()) { "Describe the playlist you want" }
      require(candidates.isNotEmpty()) { "Sync your library before generating a playlist" }
      AiDebugLog.clear()
      AiDebugLog.info(
        "Smart playlist request: target=${request.targetCount}, candidates=${candidates.size}, promptChars=${request.prompt.length}",
      )

      val modelPath =
        runtimeConfig.modelPath?.takeIf { it.isNotBlank() }
          ?: error("Gemma model path is not configured")
      val modelFile = File(modelPath)
      if (!modelFile.exists()) {
        error(
          "Gemma model not found at ${modelFile.absolutePath}. Put a .litertlm model in ${modelFile.parentFile?.absolutePath ?: "the configured model folder"}.",
        )
      }

      val cacheDir = runtimeConfig.cacheDir?.takeIf { it.isNotBlank() }
      AiDebugLog.info(
        "Gemma model=${modelFile.name}, sizeMb=${modelFile.length() / (1024 * 1024)}, cacheDir=${cacheDir ?: "default"}",
      )
      val backends = listOf(Backend.GPU(), Backend.CPU())
      var lastError: Throwable? = null
      for (backend in backends) {
        runCatching {
          return@withContext generateWithBackend(
            request = request,
            candidates = candidates,
            modelPath = modelFile.absolutePath,
            cacheDir = cacheDir,
            backend = backend,
          )
        }.onFailure { error ->
          lastError = error
          AiDebugLog.warn("LiteRT-LM ${backend.name} generation failed", error)
        }
      }
      throw lastError ?: IllegalStateException("LiteRT-LM generation failed")
    }

  private fun generateWithBackend(
    request: SmartPlaylistRequest,
    candidates: List<CandidateSong>,
    modelPath: String,
    cacheDir: String?,
    backend: Backend,
  ): SmartPlaylistPreview {
    val engineConfig =
      EngineConfig(
        modelPath = modelPath,
        backend = backend,
        cacheDir = cacheDir,
      )
    Engine(engineConfig).use { engine ->
      AiDebugLog.info("Initializing LiteRT-LM engine backend=${backend.name}")
      engine.initialize()
      AiDebugLog.info("LiteRT-LM engine initialized backend=${backend.name}")

      val libraryTool = PlaylistLibraryTool(candidates)
      val conversationConfig =
        ConversationConfig(
          systemInstruction = Contents.of(GEMMA_SYSTEM_INSTRUCTION),
          tools = listOf(tool(libraryTool)),
          samplerConfig =
            SamplerConfig(
              topK = 40,
              topP = 0.92,
              temperature = 0.35,
              seed = 21,
            ),
          automaticToolCalling = false,
        )

      engine.createConversation(conversationConfig).use { conversation ->
        val prompt = SmartPlaylistPlanner.buildGemmaPrompt(request)
        AiDebugLog.info("Gemma prompt chars=${prompt.length}")
        var message = conversation.sendMessage(Message.user(prompt))
        repeat(MAX_TOOL_ROUNDS) { round ->
          AiDebugLog.info(
            "Gemma round=${round + 1}, textChars=${message.text().length}, toolCalls=${message.toolCalls.size}",
          )
          if (message.toolCalls.isEmpty()) {
            error("Gemma did not call submit_playlist")
          }

          val toolResponses =
            message.toolCalls.map { toolCall ->
              Content.ToolResponse(toolCall.name, libraryTool.execute(toolCall))
            }

          libraryTool.submittedPlaylist?.let { submittedPlaylist ->
            val wanted = request.targetCount.coerceIn(MIN_SMART_PLAYLIST_SIZE, MAX_SMART_PLAYLIST_SIZE)
            if (submittedPlaylist.aliases.size >= wanted || candidates.size < wanted) {
              AiDebugLog.info("Using submit_playlist result: aliases=${submittedPlaylist.aliases.size}")
              return SmartPlaylistPlanner.validateSubmittedPlaylist(
                submittedPlaylist,
                candidates,
                request.targetCount,
              )
            }
            AiDebugLog.warn(
              "Gemma submitted ${submittedPlaylist.aliases.size}/$wanted aliases; requesting completion",
            )
          }

          message = conversation.sendMessage(Message.tool(Contents.of(toolResponses)))
        }
        error("Gemma did not call submit_playlist within $MAX_TOOL_ROUNDS tool rounds")
      }
    }
  }

  private fun Message.text(): String =
    contents.contents
      .filterIsInstance<Content.Text>()
      .joinToString(separator = "") { it.text }
      .ifBlank { toString() }

  private companion object {
    const val MAX_TOOL_ROUNDS = 8
    const val GEMMA_SYSTEM_INSTRUCTION =
      "You are an on-device playlist generator. You must inspect the user's local library with tools and return only valid JSON."
  }
}

private class PlaylistLibraryTool(
  private val candidates: List<CandidateSong>,
) : ToolSet {
  private val byAlias = candidates.associateBy { it.alias }
  var submittedPlaylist: SubmittedPlaylist? = null
    private set

  fun execute(toolCall: ToolCall): Map<String, Any> {
    AiDebugLog.info("Gemma requested tool=${toolCall.name}")
    return when (toolCall.name) {
      "search_songs" ->
        searchSongs(
          query = toolCall.arguments.stringValue("query"),
          limit = toolCall.arguments.intValue("limit", 24),
        )
      "list_songs" ->
        listSongs(
          offset = toolCall.arguments.intValue("offset", 0),
          limit = toolCall.arguments.intValue("limit", 24),
        )
      "get_songs" ->
        getSongs(
          aliasesCsv = toolCall.arguments.aliasesCsvValue(),
        )
      "submit_playlist" ->
        submitPlaylist(
          name = toolCall.arguments.stringValue("name").ifBlank { "Smart Playlist" },
          description = toolCall.arguments.stringValue("description"),
          aliasesCsv = toolCall.arguments.aliasesCsvValue(),
        )
      else ->
        mapOf(
          "error" to "Unknown tool: ${toolCall.name}",
        )
    }
  }

  @Tool(
    description = "Search the music library by title, artist, album, genre, year, or playlist request terms.",
  )
  fun searchSongs(
    @ToolParam(description = "Search text, such as an artist, genre, era, mood, or the user's playlist request.")
    query: String,
    @ToolParam(description = "Maximum number of songs to return. Use 1 to 80.")
    limit: Int,
  ): Map<String, Any> {
    val boundedLimit = limit.coerceIn(1, MAX_TOOL_RESULTS)
    val queryTerms = query.tokenize()
    val results =
      candidates
        .asSequence()
        .map { it to it.searchScore(queryTerms) }
        .filter { (_, score) -> queryTerms.isEmpty() || score > 0 }
        .sortedWith(compareByDescending<Pair<CandidateSong, Int>> { it.second }.thenBy { it.first.song.safeName() })
        .take(boundedLimit)
        .map { (candidate, _) -> candidate.summary() }
        .toList()

    AiDebugLog.info("Gemma tool searchSongs queryChars=${query.length}, results=${results.size}")
    return mapOf(
      "totalLibrarySongs" to candidates.size,
      "returned" to results.size,
      "songs" to results,
    )
  }

  @Tool(description = "List a page from the user's complete music library sorted by the app's stable candidate order.")
  fun listSongs(
    @ToolParam(description = "Zero-based offset into the complete library.")
    offset: Int,
    @ToolParam(description = "Maximum number of songs to return. Use 1 to 80.")
    limit: Int,
  ): Map<String, Any> {
    val safeOffset = offset.coerceAtLeast(0)
    val boundedLimit = limit.coerceIn(1, MAX_TOOL_RESULTS)
    val page = candidates.drop(safeOffset).take(boundedLimit).map { it.summary() }
    AiDebugLog.info("Gemma tool listSongs offset=$safeOffset, returned=${page.size}")
    return mapOf(
      "totalLibrarySongs" to candidates.size,
      "offset" to safeOffset,
      "returned" to page.size,
      "songs" to page,
    )
  }

  @Tool(description = "Return exact metadata for selected song aliases.")
  fun getSongs(
    @ToolParam(description = "Comma-separated aliases, for example s1,s22,s103.")
    aliasesCsv: String,
  ): Map<String, Any> {
    val aliases =
      aliasesCsv
        .split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val songs = aliases.mapNotNull { byAlias[it] }.map { it.summary() }
    AiDebugLog.info("Gemma tool getSongs requested=${aliases.size}, returned=${songs.size}")
    return mapOf(
      "requested" to aliases.size,
      "returned" to songs.size,
      "songs" to songs,
    )
  }

  @Tool(
    description = "Submit the final playlist selection after choosing the final ordered aliases.",
  )
  fun submitPlaylist(
    @ToolParam(description = "Short playlist name.")
    name: String,
    @ToolParam(description = "Short playlist description.")
    description: String,
    @ToolParam(description = "Comma-separated ordered song aliases, for example s1,s22,s103.")
    aliasesCsv: String,
  ): Map<String, Any> {
    val aliases =
      aliasesCsv
        .split(',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .filter { it in byAlias }
    submittedPlaylist = SubmittedPlaylist(name = name, description = description, aliases = aliases)
    AiDebugLog.info("Gemma tool submitPlaylist aliases=${aliases.size}")
    return mapOf(
      "accepted" to aliases.size,
      "message" to "Playlist submitted to app",
    )
  }

  private fun CandidateSong.summary(): Map<String, Any> {
    val song = song
    return buildMap {
      put("alias", alias)
      put("title", song.safeName())
      song.artists?.takeIf { it.isNotEmpty() }?.let { put("artists", it) }
      song.album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
      song.albumArtists?.takeIf { it.isNotEmpty() }?.let { put("albumArtists", it) }
      song.genres?.takeIf { it.isNotEmpty() }?.let { put("genres", it) }
      song.year?.let { put("year", it) }
      song.duration?.let { put("durationSeconds", it) }
      song.playCount?.let { put("playCount", it) }
      song.isFavorite?.let { put("favorite", it) }
    }
  }

  private fun CandidateSong.searchScore(queryTerms: Set<String>): Int {
    if (queryTerms.isEmpty()) return score
    val song = song
    val searchable =
      listOfNotNull(
        song.safeName(),
        song.album,
        song.artists?.joinToString(" "),
        song.albumArtists?.joinToString(" "),
        song.genres?.joinToString(" "),
        song.year?.toString(),
      ).joinToString(" ").lowercase()

    var result = 0
    queryTerms.forEach { term ->
      if (searchable.contains(term)) result += 20
    }
    val promptYears = queryTerms.mapNotNull { it.toIntOrNull() }
    val songYear = song.year
    if (songYear != null && promptYears.any { abs(it - songYear) <= 3 }) result += 18
    return result + (score / 10)
  }

  private fun String.tokenize(): Set<String> =
    lowercase()
      .split(Regex("[^a-z0-9]+"))
      .filter { it.length >= 3 }
      .filterNot { it in STOP_WORDS }
      .toSet()

  private fun Song.safeName(): String = runCatching { name }.getOrNull().orEmpty()

  private fun Map<String, Any?>.stringValue(name: String): String = this[name]?.toString().orEmpty()

  private fun Map<String, Any?>.intValue(
    name: String,
    default: Int,
  ): Int =
    when (val value = this[name]) {
      is Number -> value.toInt()
      is String -> value.toIntOrNull() ?: default
      else -> default
    }

  private fun Map<String, Any?>.aliasesCsvValue(): String {
    val value = this["aliasesCsv"] ?: this["aliases_csv"] ?: this["aliases"] ?: this["alias"]
    return when (value) {
      is Iterable<*> -> value.joinToString(",") { it.toString() }
      is Array<*> -> value.joinToString(",") { it.toString() }
      else -> value?.toString().orEmpty()
    }
  }

  private companion object {
    const val MAX_TOOL_RESULTS = 80
    val STOP_WORDS =
      setOf(
        "the",
        "and",
        "for",
        "with",
        "from",
        "that",
        "this",
        "make",
        "playlist",
        "songs",
        "music",
        "track",
        "tracks",
      )
  }
}

data class SubmittedPlaylist(
  val name: String,
  val description: String,
  val aliases: List<String>,
)
