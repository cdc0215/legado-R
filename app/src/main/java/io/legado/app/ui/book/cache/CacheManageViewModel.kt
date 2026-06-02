package io.legado.app.ui.book.cache

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.CacheBookManifest
import io.legado.app.help.book.CacheManifestHelper
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.removeType
import io.legado.app.model.CacheBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.getMediaRequest
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.isJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.Locale

class CacheManageViewModel(application: Application) : BaseViewModel(application) {

    val itemsLiveData = MutableLiveData<List<CacheBookItem>>()
    val summaryLiveData = MutableLiveData<CacheSummary>()
    val loadingLiveData = MutableLiveData<Boolean>()

    private var loadJob: Job? = null
    private val selectedSourceKeys = hashMapOf<String, String>()
    var mode: CacheManageMode = CacheManageMode.BOOK
        private set

    fun isLoading(): Boolean = loadJob?.isActive == true

    fun load(mode: CacheManageMode = this.mode) {
        this.mode = mode
        loadJob?.cancel()
        lateinit var job: Job
        job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            loadingLiveData.postValue(true)
            try {
                val currentBooks = getBooks(mode)
                val currentBookUrls = currentBooks.mapTo(hashSetOf()) { it.bookUrl }
                val cacheDirs = CacheManifestHelper.listCacheDirs()
                val cacheDirNames = cacheDirs.mapTo(hashSetOf()) { it.name }
                val manifests = CacheManifestHelper.listManifests(cacheDirs)
                val manifestByBookUrl = manifests.associateBy { it.bookUrl }
                val currentItems = currentBooks
                    .asSequence()
                    .mapNotNull { book ->
                        buildCacheBookItem(
                            book = book,
                            mode = mode,
                            knownManifest = manifestByBookUrl[book.bookUrl],
                            cacheDirNames = cacheDirNames
                        )
                    }
                    .toList()
                val manifestItems = manifests
                    .asSequence()
                    .filter { it.matches(mode) }
                    .filterNot { currentBookUrls.contains(it.bookUrl) }
                    .mapNotNull { manifest -> buildCacheBookItem(manifest, mode) }
                    .toList()
                val items = groupByBook(currentItems + manifestItems)
                ensureActive()
                val storageBreakdown = buildStorageBreakdown()
                itemsLiveData.postValue(items)
                summaryLiveData.postValue(
                    CacheSummary(
                        bookCount = items.size,
                        cachedChapterCount = items.sumOf { it.cachedCount },
                        currentModeSize = items.sumOf { it.storageSizeBytes },
                        totalCacheSize = getAppStorageSize(),
                        storageDetails = storageBreakdown,
                        mode = mode
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (loadJob === job) {
                    loadingLiveData.postValue(false)
                }
            }
        }
        loadJob = job
        job.start()
    }

    fun loadStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val storageBreakdown = buildStorageBreakdown()
            summaryLiveData.postValue(
                CacheSummary(
                    bookCount = 0,
                    cachedChapterCount = 0,
                    currentModeSize = 0L,
                    totalCacheSize = getAppStorageSize(),
                    storageDetails = storageBreakdown,
                    mode = mode
                )
            )
        }
    }

    fun deleteStorageDetail(target: CacheStorageDeleteTarget, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteStorageTarget(target)
            val storageBreakdown = buildStorageBreakdown()
            summaryLiveData.postValue(
                CacheSummary(
                    bookCount = 0,
                    cachedChapterCount = 0,
                    currentModeSize = 0L,
                    totalCacheSize = getAppStorageSize(),
                    storageDetails = storageBreakdown,
                    mode = mode
                )
            )
            withContext(Dispatchers.Main) {
                onDone()
            }
        }
    }

    fun selectSource(groupKey: String, sourceKey: String) {
        selectedSourceKeys[groupKey] = sourceKey
        load()
    }

    fun deleteBookCache(book: Book, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteMediaCache(book)
            BookHelp.clearCache(book)
            CacheManifestHelper.delete(book)
            withContext(Dispatchers.Main) {
                onDone()
            }
            load(mode)
        }
    }

    fun deleteBookCaches(books: List<Book>, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            books.forEach {
                deleteMediaCache(it)
                BookHelp.clearCache(it)
                CacheManifestHelper.delete(it)
            }
            withContext(Dispatchers.Main) {
                onDone()
            }
            load(mode)
        }
    }

    suspend fun getChapterItems(book: Book, key: String? = null): List<CacheChapterItem> {
        return getChapterItems(book, key, CacheChapterFilter.ALL)
    }

    suspend fun getChapterItems(
        book: Book,
        key: String? = null,
        filter: CacheChapterFilter = CacheChapterFilter.ALL
    ): List<CacheChapterItem> {
        return withContext(Dispatchers.IO) {
            val cacheNames = if (book.isMedia) emptySet() else getCacheFileNames(book)
            val manifest = CacheManifestHelper.read(book)
            val dbChapters = if (key.isNullOrBlank()) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            } else {
                appDb.bookChapterDao.search(book.bookUrl, key)
            }
            if (book.isMedia && CacheManifestHelper.mergeResourceUrls(dbChapters, manifest)) {
                appDb.bookChapterDao.update(*dbChapters.toTypedArray())
            }
            val chapters = dbChapters.takeIf { it.isNotEmpty() }
                ?: CacheManifestHelper.toChapters(manifest ?: return@withContext emptyList())
                    .filterByKey(key)
            chapters
                .asSequence()
                .filterNot { it.isVolume }
                .mapNotNull { chapter ->
                    val cached = isChapterCached(
                        book,
                        chapter,
                        cacheNames,
                        validateImageContent = false
                    )
                    when (filter) {
                        CacheChapterFilter.CACHED -> if (!cached) return@mapNotNull null
                        CacheChapterFilter.UNCACHED -> if (cached) return@mapNotNull null
                        CacheChapterFilter.ALL -> Unit
                    }
                    CacheChapterItem(chapter = chapter, cached = cached)
                }
                .toList()
        }
    }

    suspend fun deleteChapterCache(book: Book, chapter: BookChapter) {
        deleteChapterCaches(book, listOf(chapter))
    }

    suspend fun deleteChapterCaches(book: Book, chapters: List<BookChapter>) {
        withContext(Dispatchers.IO) {
            if (chapters.isEmpty()) return@withContext
            chapters.forEach { chapter ->
                if (book.isMedia) {
                    ExoPlayerHelper.removeMediaCache(chapter.resourceUrl)
                }
                BookHelp.delChapterCache(book, chapter)
            }
            refreshManifest(book)
        }
    }

    fun cacheBookChapters(book: Book, chapters: List<BookChapter>): Int {
        if (book.isMedia || book.isLocal) return 0
        val indexes = chapters
            .asSequence()
            .filterNot { it.isVolume }
            .map { it.index }
            .distinct()
            .sorted()
            .toList()
        if (indexes.isEmpty()) return 0
        indexes.toRanges().forEach { (start, end) ->
            CacheBook.start(appCtx, book, start, end)
        }
        return indexes.size
    }

    suspend fun cacheAudioChapters(
        book: Book,
        chapters: List<BookChapter>,
        reloadOnFinished: Boolean = true
    ): Int {
        return cacheMediaChapters(book, chapters, reloadOnFinished)
    }

    suspend fun cacheMediaChapters(
        book: Book,
        chapters: List<BookChapter>,
        reloadOnFinished: Boolean = true
    ): Int {
        if (!book.isMedia) return 0
        val targets = withContext(Dispatchers.IO) {
            val realChapters = chapters
                .asSequence()
                .filterNot { it.isVolume }
                .toList()
            if (CacheManifestHelper.mergeResourceUrls(realChapters, CacheManifestHelper.read(book))) {
                appDb.bookChapterDao.update(*realChapters.toTypedArray())
            }
            realChapters
                .asSequence()
                .filterNot { ExoPlayerHelper.isMediaCached(it.resourceUrl) }
                .toList()
        }
        if (targets.isEmpty()) return 0
        val started = AudioCacheTaskManager.start(
            book = book,
            chapters = targets,
            resolver = ::resolveMediaRequest,
            onChapterResolved = { chapter, request ->
                if (chapter.resourceUrl != request.url) {
                    chapter.resourceUrl = request.url
                    appDb.bookChapterDao.update(chapter)
                }
            },
            onFinished = {
                refreshManifest(book)
                if (reloadOnFinished && mode == book.cacheManageMode) {
                    load(mode)
                }
            }
        )
        if (started && mode == book.cacheManageMode) {
            load(mode)
        }
        return if (started) targets.size else 0
    }

    suspend fun restoreCacheToBookshelf(item: CacheBookItem): Boolean {
        return withContext(Dispatchers.IO) {
            val manifest = item.manifest ?: CacheManifestHelper.read(item.book) ?: return@withContext false
            val sameUrlBook = appDb.bookDao.getBook(manifest.bookUrl)
            val sameNameBook = appDb.bookDao.getBook(manifest.name, manifest.author)
            val cacheBook = CacheManifestHelper.toBook(manifest).apply {
                removeType(BookType.notShelf)
                sameUrlBook?.let {
                    group = it.group
                    order = it.order
                    durChapterIndex = it.durChapterIndex
                    durChapterTitle = it.durChapterTitle
                    durChapterPos = it.durChapterPos
                    readConfig = it.readConfig
                } ?: sameNameBook?.let {
                    group = it.group
                    order = it.order
                    durChapterIndex = it.durChapterIndex
                    durChapterTitle = it.durChapterTitle
                    durChapterPos = it.durChapterPos
                    readConfig = it.readConfig
                }
            }
            when {
                sameUrlBook != null -> appDb.bookDao.update(cacheBook)
                sameNameBook != null -> appDb.bookDao.replace(sameNameBook, cacheBook)
                else -> appDb.bookDao.insert(cacheBook)
            }
            val chapters = CacheManifestHelper.toChapters(manifest, cacheBook.bookUrl)
            if (chapters.isNotEmpty()) {
                appDb.bookChapterDao.delByBook(cacheBook.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
            }
            true
        }
    }

    suspend fun createCachePackage(book: Book): File {
        return withContext(Dispatchers.IO) {
            val cacheDir = BookHelp.getCacheDir(book)
            val outDir = File(appCtx.externalCache, "cache_package").apply {
                if (!exists()) mkdirs()
            }
            val fileName = "${book.name}_${book.author}_${System.currentTimeMillis()}"
                .normalizeFileName()
                .ifBlank { "cache_${System.currentTimeMillis()}" }
            val zipFile = File(outDir, "$fileName.zip").apply {
                if (exists()) delete()
            }
            if (book.isMedia) {
                return@withContext createMediaCachePackage(book, cacheDir, outDir, fileName, zipFile)
            }
            if (!cacheDir.exists() || cacheDir.listFiles().isNullOrEmpty()) {
                throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
            }
            if (!ZipUtils.zipFile(cacheDir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
                throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
            }
            zipFile
        }
    }

    private fun createMediaCachePackage(
        book: Book,
        cacheDir: File,
        outDir: File,
        fileName: String,
        zipFile: File
    ): File {
        val packageDir = File(outDir, "${fileName}_media").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        var hasCache = false
        if (cacheDir.exists() && !cacheDir.listFiles().isNullOrEmpty()) {
            cacheDir.copyRecursively(File(packageDir, "chapter_cache"), overwrite = true)
            hasCache = true
        }
        val mediaDir = File(packageDir, "media_cache").apply { mkdirs() }
        val chapters = (appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.read(book)?.let(CacheManifestHelper::toChapters).orEmpty())
            .filterNot { it.isVolume }
            .mapNotNull { chapter ->
                val chapterDir = File(mediaDir, chapter.index.toString())
                if (!ExoPlayerHelper.isMediaCached(chapter.resourceUrl)) {
                    chapterDir.deleteRecursively()
                    return@mapNotNull null
                }
                val fileCount = ExoPlayerHelper.copyMediaCache(chapter.resourceUrl, chapterDir)
                if (fileCount <= 0) {
                    chapterDir.deleteRecursively()
                    return@mapNotNull null
                }
                hasCache = true
                MediaCacheManifest.Chapter(
                    index = chapter.index,
                    title = chapter.title,
                    url = chapter.url,
                    resourceUrl = chapter.resourceUrl,
                    fileCount = fileCount
                )
            }
        if (!hasCache) {
            packageDir.deleteRecursively()
            throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
        }
        File(packageDir, "manifest.json").writeText(
            GSON.toJson(
                MediaCacheManifest(
                    bookName = book.name,
                    author = book.author,
                    bookUrl = book.bookUrl,
                    chapters = chapters
                )
            )
        )
        val success = ZipUtils.zipFile(packageDir, zipFile)
        packageDir.deleteRecursively()
        if (!success || !zipFile.exists() || zipFile.length() <= 0L) {
            throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
        }
        return zipFile
    }

    private fun groupByBook(items: List<CacheBookItem>): List<CacheBookItem> {
        return items
            .groupBy { it.groupKey }
            .values
            .mapNotNull { group ->
                val variants = group
                    .sortedWith(
                        compareByDescending<CacheBookItem> { if (it.taskState.isVisibleAudioTask()) 1 else 0 }
                            .thenByDescending { it.cachedCount }
                            .thenByDescending { it.storageSizeBytes }
                            .thenBy { it.sourceName }
                    )
                    .map { it.toSourceVariant() }
                val groupKey = group.firstOrNull()?.groupKey ?: return@mapNotNull null
                val selectedKey = selectedSourceKeys[groupKey]
                val selected = group.firstOrNull { it.sourceKey == selectedKey }
                    ?: group.firstOrNull { it.taskState.isVisibleAudioTask() }
                    ?: group.maxWithOrNull(
                        compareBy<CacheBookItem> { it.cachedCount }
                            .thenBy { it.storageSizeBytes }
                            .thenBy { it.totalChapterCount }
                    )
                    ?: group.first()
                selected.copy(sourceVariants = variants)
            }
            .sortedWith(
                compareByDescending<CacheBookItem> { it.cachedCount }
                    .thenByDescending { it.storageSizeBytes }
                    .thenBy { it.book.name }
                    .thenBy { it.sourceName }
            )
    }

    private fun buildCacheBookItem(
        book: Book,
        mode: CacheManageMode,
        knownManifest: CacheBookManifest? = null,
        cacheDirNames: Set<String> = emptySet()
    ): CacheBookItem? {
        val taskState = AudioCacheTaskManager.snapshot(book.bookUrl)
        if (mode.isMedia) {
            return buildMediaCacheBookItem(book, mode, knownManifest, taskState)
        }
        if (knownManifest == null &&
            taskState?.active != true &&
            !cacheDirNames.contains(book.getFolderName())
        ) {
            return null
        }
        val cacheNames = getCacheFileNames(book)
        val needsChapterList = book.totalChapterNum <= 0 || book.isNotShelf
        var manifest = knownManifest ?: CacheManifestHelper.read(book)
        val dbChapters = if (needsChapterList) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
        } else {
            emptyList()
        }
        val chapters = dbChapters.takeIf { it.isNotEmpty() }
            ?: manifest?.let(CacheManifestHelper::toChapters)
            ?: emptyList()
        val rawCachedCount = getFastCachedCount(cacheNames)
        if (rawCachedCount <= 0 && taskState?.active != true) {
            CacheManifestHelper.delete(book)
            return null
        }
        if (book.isNotShelf && manifest == null) {
            manifest = CacheManifestHelper.refresh(book, chapters)
        }
        val totalChapterCount = book.totalChapterNum.takeIf { it > 0 }
            ?: chapters.size.takeIf { it > 0 }
            ?: rawCachedCount
        val cachedCount = rawCachedCount.coerceAtMost(totalChapterCount)
        val storage = getBookStorage(book)
        return CacheBookItem(
            book = book,
            mode = mode,
            groupKey = book.cacheGroupKey(mode),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            storageSizeBytes = storage.totalBytes,
            storageSummary = storage.displayText,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = !book.isNotShelf,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun buildMediaCacheBookItem(
        book: Book,
        mode: CacheManageMode,
        initialManifest: CacheBookManifest?,
        taskState: AudioCacheTaskState?
    ): CacheBookItem? {
        var manifest = initialManifest
        if (book.isNotShelf && manifest == null) {
            manifest = CacheManifestHelper.refresh(book)
        }
        val hasVisibleTask = taskState.isVisibleAudioTask()
        if (manifest == null && !hasVisibleTask) return null
        val candidateCachedIndexes = manifest.cachedIndexes()
        val manifestChapters = manifest?.let(CacheManifestHelper::toChapters).orEmpty()
        val realCachedCount = getAudioCachedCount(manifestChapters, candidateCachedIndexes)
        val taskCompletedCount = taskState?.completedChapters ?: 0
        val rawCachedCount = maxOf(realCachedCount, taskCompletedCount)
        if (rawCachedCount <= 0 && !hasVisibleTask) {
            CacheManifestHelper.delete(book)
            return null
        }
        val totalChapterCount = book.totalChapterNum.takeIf { it > 0 }
            ?: manifest?.totalChapterNum?.takeIf { it > 0 }
            ?: manifestChapters.size.takeIf { it > 0 }
            ?: taskState?.totalChapters?.takeIf { it > 0 }
            ?: rawCachedCount.coerceAtLeast(1)
        val cachedCount = rawCachedCount.coerceAtMost(totalChapterCount)
        val storage = getBookStorage(book, manifestChapters)
        return CacheBookItem(
            book = book,
            mode = mode,
            groupKey = book.cacheGroupKey(mode),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            storageSizeBytes = storage.totalBytes,
            storageSummary = storage.displayText,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = !book.isNotShelf,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun buildCacheBookItem(
        manifest: CacheBookManifest,
        mode: CacheManageMode
    ): CacheBookItem? {
        val book = CacheManifestHelper.toBook(manifest)
        val chapters = CacheManifestHelper.toChapters(manifest)
        val cacheNames = getCacheFileNames(book)
        val rawCachedCount = if (mode.isMedia) {
            getAudioCachedCount(chapters, manifest.cachedIndexes())
        } else {
            chapters.count {
                isChapterCached(book, it, cacheNames, validateImageContent = false)
            }
        }
        if (rawCachedCount <= 0) {
            if (mode.isMedia) {
                CacheManifestHelper.delete(manifest)
            }
            return null
        }
        val totalChapterCount = manifest.totalChapterNum.takeIf { it > 0 }
            ?: chapters.size.takeIf { it > 0 }
            ?: rawCachedCount
        val storage = getBookStorage(book, chapters)
        return CacheBookItem(
            book = book,
            mode = mode,
            groupKey = book.cacheGroupKey(mode),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = rawCachedCount.coerceAtMost(totalChapterCount),
            totalChapterCount = totalChapterCount,
            storageSizeBytes = storage.totalBytes,
            storageSummary = storage.displayText,
            manifest = manifest,
            inBookshelf = false,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun getFastCachedCount(cacheNames: Set<String>): Int {
        return cacheNames.count { it.endsWith(".nb") }
    }

    private fun getAudioCachedCount(chapters: List<BookChapter>): Int {
        return getAudioCachedCount(chapters, cachedIndexes = null)
    }

    private fun getAudioCachedCount(
        chapters: List<BookChapter>,
        cachedIndexes: Set<Int>? = null
    ): Int {
        return chapters
            .asSequence()
            .filterNot { it.isVolume }
            .filter { cachedIndexes == null || it.index in cachedIndexes }
            .count { ExoPlayerHelper.isMediaCached(it.resourceUrl) }
    }

    private fun getBookStorage(
        book: Book,
        chapters: List<BookChapter> = emptyList()
    ): CacheBookStorage {
        val cacheDir = BookHelp.getCacheDir(book)
        val manifestSize = File(cacheDir, CacheManifestHelper.MANIFEST_FILE_NAME).fileSize()
        val imageDir = File(cacheDir, "images")
        val imageSize = if (book.isImage) imageDir.directorySize() else 0L
        val chapterSize = cacheDir.childrenSize(excludes = setOf("images")) - manifestSize
        val mediaSize = if (book.isMedia) {
            chapters
                .asSequence()
                .filterNot { it.isVolume }
                .sumOf { ExoPlayerHelper.getMediaCacheSize(it.resourceUrl) }
        } else {
            0L
        }
        val parts = buildList {
            if (chapterSize > 0L) add(context.getString(R.string.cache_manage_size_chapters, formatBytes(chapterSize)))
            if (imageSize > 0L) add(context.getString(R.string.cache_manage_size_images, formatBytes(imageSize)))
            if (mediaSize > 0L) add(context.getString(R.string.cache_manage_size_media, formatBytes(mediaSize)))
            if (manifestSize > 0L) add(context.getString(R.string.cache_manage_size_manifest, formatBytes(manifestSize)))
        }
        val total = chapterSize + imageSize + mediaSize + manifestSize
        return CacheBookStorage(
            totalBytes = total,
            displayText = parts.joinToString(" · ")
        )
    }

    private fun buildStorageBreakdown(): List<CacheStorageDetail> {
        val internalCache = appCtx.cacheDir
        val externalCache = appCtx.externalCache
        val codeCache = appCtx.codeCacheDir
        val dataDir = File(appCtx.applicationInfo.dataDir)
        val filesDir = appCtx.filesDir
        val externalFiles = appCtx.externalFiles
        val sameCacheRoot = internalCache.absolutePath == externalCache.absolutePath
        val knownInternal = listOf(
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_cover_thumbs),
                listOf(File(internalCache, "cover_thumbs_v2")),
                CacheStorageDeleteTarget.COVER_THUMBS
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_discover_rss),
                listOf(File(internalCache, "ACache"), File(filesDir, "ACache")),
                CacheStorageDeleteTarget.DISCOVER_RSS
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_glide),
                listOf(File(internalCache, "image_manager_disk_cache")),
                CacheStorageDeleteTarget.GLIDE
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_webview),
                listOf(
                    File(dataDir, "app_webview"),
                    File(internalCache, "WebView"),
                    File(codeCache, "com.android.webview"),
                    File(codeCache, "WebView")
                )
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_share_js),
                listOf(File(internalCache, "shareJs")),
                CacheStorageDeleteTarget.SHARE_JS
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_tts),
                listOf(File(internalCache, "httpTTS"), File(internalCache, "httpTTS_cache")),
                CacheStorageDeleteTarget.TTS
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_epub_temp),
                listOf(File(internalCache, "epub-fonts"), File(internalCache, "epub-debug")),
                CacheStorageDeleteTarget.EPUB_TEMP
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_image_temp),
                listOf(File(internalCache, "tmp"), File(internalCache, "image_crop_source")),
                CacheStorageDeleteTarget.IMAGE_TEMP
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_so_download),
                listOf(File(internalCache, "so_download")),
                CacheStorageDeleteTarget.SO_DOWNLOAD
            )
        )
        val knownExternal = listOf(
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_audio_offline),
                listOf(File(externalCache, "audio_exoplayer"), File(externalCache, "audio_exoplayer_complete"))
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_video_preload),
                listOf(File(externalCache, "exoplayer"))
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_player_temp),
                listOf(File(externalCache, "video_temp"), File(externalCache, "video_temp_cache")),
                CacheStorageDeleteTarget.PLAYER_TEMP
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_cache_package),
                listOf(File(externalCache, "cache_package")),
                CacheStorageDeleteTarget.CACHE_PACKAGE
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_read_config),
                listOf(File(externalCache, "readConfig"), File(externalCache, "readConfig.zip")),
                CacheStorageDeleteTarget.READ_CONFIG
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_upload_temp),
                listOf(File(externalCache, "upload")),
                CacheStorageDeleteTarget.UPLOAD
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_logs),
                listOf(
                    File(externalCache, "logs"),
                    File(externalCache, "crash"),
                    File(externalCache, "logcat.txt"),
                    File(externalCache, "logs.zip"),
                    File(externalCache, "heapDump")
                ),
                CacheStorageDeleteTarget.LOGS
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_archive_temp),
                listOf(File(externalCache, "ArchiveTemp")),
                CacheStorageDeleteTarget.ARCHIVE_TEMP
            )
        )
        val knownExternalFiles = listOf(
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_book_source_data),
                listOf(File(externalFiles, "ruleData/book"))
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_rss_source_data),
                listOf(File(externalFiles, "ruleData/rss"))
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_theme_packages),
                listOf(File(externalFiles, "themePackages"), File(externalFiles, "themePackageImports"))
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_read_style_assets),
                listOf(
                    File(externalFiles, "font"),
                    File(externalFiles, "bg"),
                    File(externalFiles, PreferKey.bgImage),
                    File(externalFiles, PreferKey.bgImageN),
                    File(externalFiles, PreferKey.bookInfoBgImage),
                    File(externalFiles, PreferKey.bookInfoBgImageN)
                )
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_navigation_assets),
                listOf(
                    File(externalFiles, "navigationBarPackages"),
                    File(externalFiles, "navigationIcons"),
                    File(externalFiles, "navigationBarTemp"),
                    File(externalFiles, "navigationBarImports")
                )
            ),
            CacheStorageGroup(
                context.getString(R.string.cache_manage_storage_cover_files),
                listOf(File(externalFiles, "covers"))
            )
        )
        val internalDetails = knownInternal.map { it.toDetail() }
        val externalDetails = knownExternal.map { it.toDetail() }
        val externalFileDetails = knownExternalFiles.map { it.toDetail() }
        val bookCacheRoot = File(BookHelp.cachePath)
        val epubRoot = File(externalFiles, "epub")
        val knownExternalFileRootNames = setOf(
            "book_cache",
            "epub",
            "ruleData",
            "themePackages",
            "themePackageImports",
            "font",
            "bg",
            PreferKey.bgImage,
            PreferKey.bgImageN,
            PreferKey.bookInfoBgImage,
            PreferKey.bookInfoBgImageN,
            "navigationBarPackages",
            "navigationIcons",
            "navigationBarTemp",
            "navigationBarImports",
            "covers"
        )
        val userDataDetails = listOf(
            CacheStorageDetail(
                context.getString(R.string.cache_manage_storage_databases),
                File(dataDir, "databases").directorySize()
            ),
            CacheStorageDetail(
                context.getString(R.string.cache_manage_storage_preferences),
                File(dataDir, "shared_prefs").directorySize() + File(dataDir, "datastore").directorySize()
            ),
            CacheStorageDetail(
                context.getString(R.string.cache_manage_storage_internal_files),
                filesDir.childrenSize(excludes = setOf("ACache"))
            )
        )
        val knownInternalCachePaths = knownInternal.cacheChildPaths(internalCache)
        val knownExternalCachePaths = knownExternal.cacheChildPaths(externalCache)
        val knownCodeCachePaths = knownInternal.cacheChildPaths(codeCache)
        val knownInternalSize = internalCache.childrenSize(excludes = knownInternalCachePaths)
            .let { internalCache.directorySize() - it }
            .coerceAtLeast(0L)
        val knownExternalSize = externalCache.childrenSize(excludes = knownExternalCachePaths)
            .let { externalCache.directorySize() - it }
            .coerceAtLeast(0L)
        val knownCodeCacheSize = codeCache.childrenSize(excludes = knownCodeCachePaths)
            .let { codeCache.directorySize() - it }
            .coerceAtLeast(0L)
        val allKnownInternalSize = if (sameCacheRoot) {
            knownInternalSize + knownExternalSize
        } else {
            knownInternalSize
        }
        val otherInternalCacheSize = (internalCache.directorySize() - allKnownInternalSize).coerceAtLeast(0L)
        val otherExternalCacheSize = if (sameCacheRoot) {
            0L
        } else {
            (externalCache.directorySize() - knownExternalSize).coerceAtLeast(0L)
        }
        val otherCodeCacheSize = (codeCache.directorySize() - knownCodeCacheSize).coerceAtLeast(0L)
        val otherUserDataSize = dataDir.childrenSize(
            excludes = setOf(
                "app_webview",
                "cache",
                "code_cache",
                "databases",
                "datastore",
                "files",
                "no_backup",
                "shared_prefs"
            )
        )
        val otherSize = appCtx.noBackupFilesDir.directorySize() +
                externalFiles.childrenSize(excludes = knownExternalFileRootNames) +
                otherUserDataSize +
                otherCodeCacheSize +
                otherInternalCacheSize +
                otherExternalCacheSize
        return buildList {
            addAll(getBookCacheStorageDetails(bookCacheRoot))
            add(CacheStorageDetail(context.getString(R.string.cache_manage_storage_local_epub), epubRoot.directorySize()))
            addAll(externalFileDetails)
            addAll(userDataDetails)
            addAll(internalDetails)
            addAll(externalDetails)
            add(CacheStorageDetail(context.getString(R.string.cache_manage_storage_other), otherSize))
        }
    }

    private fun getBookCacheStorageDetails(bookCacheRoot: File): List<CacheStorageDetail> {
        val knownPaths = hashSetOf<String>()
        var textSize = 0L
        var audioSize = 0L
        var videoSize = 0L
        var mangaSize = 0L
        appDb.bookDao.all.forEach { book ->
            val cacheDir = BookHelp.getCacheDir(book)
            val path = cacheDir.absolutePath
            if (!knownPaths.add(path)) return@forEach
            val size = cacheDir.directorySize()
            when {
                book.isImage -> mangaSize += size
                book.isVideo -> videoSize += size
                book.isAudio -> audioSize += size
                else -> textSize += size
            }
        }
        val otherSize = bookCacheRoot.listFiles()
            ?.asSequence()
            ?.filterNot { it.absolutePath in knownPaths }
            ?.sumOf { it.directorySize() }
            ?: 0L
        return listOf(
            CacheStorageDetail(context.getString(R.string.cache_manage_storage_text_books), textSize),
            CacheStorageDetail(context.getString(R.string.cache_manage_storage_audio_books), audioSize),
            CacheStorageDetail(context.getString(R.string.cache_manage_storage_video_books), videoSize),
            CacheStorageDetail(context.getString(R.string.cache_manage_storage_manga_books), mangaSize),
            CacheStorageDetail(context.getString(R.string.cache_manage_storage_other_books), otherSize)
        )
    }

    private fun deleteStorageTarget(target: CacheStorageDeleteTarget) {
        val internalCache = appCtx.cacheDir
        val externalCache = appCtx.externalCache
        val filesDir = appCtx.filesDir
        val paths = when (target) {
            CacheStorageDeleteTarget.COVER_THUMBS -> listOf(File(internalCache, "cover_thumbs_v2"))
            CacheStorageDeleteTarget.DISCOVER_RSS -> listOf(File(internalCache, "ACache"), File(filesDir, "ACache"))
            CacheStorageDeleteTarget.GLIDE -> {
                Glide.get(appCtx).clearDiskCache()
                emptyList()
            }
            CacheStorageDeleteTarget.SHARE_JS -> listOf(File(internalCache, "shareJs"))
            CacheStorageDeleteTarget.TTS -> listOf(File(internalCache, "httpTTS"), File(internalCache, "httpTTS_cache"))
            CacheStorageDeleteTarget.EPUB_TEMP -> listOf(File(internalCache, "epub-fonts"), File(internalCache, "epub-debug"))
            CacheStorageDeleteTarget.IMAGE_TEMP -> listOf(File(internalCache, "tmp"), File(internalCache, "image_crop_source"))
            CacheStorageDeleteTarget.SO_DOWNLOAD -> listOf(File(internalCache, "so_download"))
            CacheStorageDeleteTarget.AUDIO -> {
                ExoPlayerHelper.clearAudioCache()
                emptyList()
            }
            CacheStorageDeleteTarget.VIDEO -> {
                ExoPlayerHelper.clearVideoCache()
                emptyList()
            }
            CacheStorageDeleteTarget.PLAYER_TEMP -> listOf(File(externalCache, "video_temp"), File(externalCache, "video_temp_cache"))
            CacheStorageDeleteTarget.CACHE_PACKAGE -> listOf(File(externalCache, "cache_package"))
            CacheStorageDeleteTarget.READ_CONFIG -> listOf(File(externalCache, "readConfig"), File(externalCache, "readConfig.zip"))
            CacheStorageDeleteTarget.UPLOAD -> listOf(File(externalCache, "upload"))
            CacheStorageDeleteTarget.LOGS -> listOf(
                File(externalCache, "logs"),
                File(externalCache, "crash"),
                File(externalCache, "logcat.txt"),
                File(externalCache, "logs.zip"),
                File(externalCache, "heapDump")
            )
            CacheStorageDeleteTarget.ARCHIVE_TEMP -> listOf(File(externalCache, "ArchiveTemp"))
        }
        paths.forEach { file ->
            if (file.exists()) FileUtils.delete(file, deleteRootDir = true)
        }
    }

    private fun getAppStorageSize(): Long {
        val roots = listOf(
            File(appCtx.applicationInfo.dataDir),
            appCtx.externalFiles,
            appCtx.externalCache
        ).distinctBy { it.absolutePath }
        return roots.sumOf { it.directorySize() }
    }

    private fun CacheBookManifest?.cachedIndexes(): Set<Int>? {
        return this
            ?.chapters
            ?.asSequence()
            ?.filter { it.cached }
            ?.mapTo(hashSetOf()) { it.index }
    }

    private fun getCacheFileNames(book: Book): Set<String> {
        val cacheDir = BookHelp.getCacheDir(book)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return emptySet()
        return cacheDir.list()?.toSet().orEmpty()
    }

    private fun isChapterCached(
        book: Book,
        chapter: BookChapter,
        cacheNames: Set<String> = getCacheFileNames(book),
        validateImageContent: Boolean = true
    ): Boolean {
        if (book.isLocal) return false
        if (book.isMedia) return ExoPlayerHelper.isMediaCached(chapter.resourceUrl)
        val hasContent = BookHelp.getChapterCacheFileNames(book, chapter).any(cacheNames::contains)
        return if (validateImageContent && book.isImage && hasContent) {
            BookHelp.hasImageContent(book, chapter)
        } else {
            hasContent
        }
    }

    private fun getBooks(mode: CacheManageMode): List<Book> {
        return when (mode) {
            CacheManageMode.BOOK -> appDb.bookDao.getByTypeOnLine(BookType.text)
            CacheManageMode.AUDIO -> appDb.bookDao.getByTypeOnLine(BookType.audio)
            CacheManageMode.VIDEO -> appDb.bookDao.getByTypeOnLine(BookType.video)
            CacheManageMode.MANGA -> appDb.bookDao.getByTypeOnLine(BookType.image)
        }
    }

    private fun deleteMediaCache(book: Book) {
        if (!book.isMedia) return
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.read(book)?.let(CacheManifestHelper::toChapters).orEmpty()
        chapters
            .forEach { ExoPlayerHelper.removeMediaCache(it.resourceUrl) }
    }

    private fun refreshManifest(book: Book) {
        CacheManifestHelper.refresh(book)
    }

    private suspend fun resolveMediaRequest(
        book: Book,
        chapter: BookChapter
    ): ExoPlayerHelper.MediaRequest {
        chapter.resourceUrl
            ?.takeIf { it.isNotBlank() }
            ?.takeIf(::isDownloadableMediaContent)
            ?.let { return ExoPlayerHelper.MediaRequest(it) }
        val source = book.getBookSource()
            ?: throw IllegalStateException(context.getString(R.string.book_source_not_found))
        val candidates = linkedSetOf<String>()
        BookHelp.getContent(book, chapter)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { content -> normalizeMediaContent(book, content) }
            ?.let(candidates::add)
        WebBook.getContentAwait(source, book, chapter, needSave = true)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { content -> normalizeMediaContent(book, content) }
            ?.let(candidates::add)
        var lastError: Throwable? = null
        for (content in candidates) {
            try {
                if (content.isJsonArray()) {
                    return ExoPlayerHelper.MediaRequest(content)
                }
                return AnalyzeUrl(
                    content,
                    source = source,
                    ruleData = book,
                    chapter = chapter,
                    coroutineContext = currentCoroutineContext()
                ).getMediaRequest()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            lastError?.localizedMessage ?: context.getString(R.string.cache_manage_audio_url_empty)
        )
    }

    private fun normalizeMediaContent(book: Book, content: String): String {
        if (!book.isVideo) return content
        if (content.startsWith("#EXTM3U")) {
            return writeVideoTempManifest(content, "m3u8")
        }
        if (!content.startsWith("<")) return content
        return writeVideoTempManifest(content, "mpd")
    }

    private fun writeVideoTempManifest(content: String, suffix: String): String {
        val dir = File(appCtx.externalCache, "video_temp_cache").apply { mkdirs() }
        val file = File(dir, "${MD5Utils.md5Encode(content)}.$suffix")
        if (!file.isFile || file.readText() != content) {
            file.writeText(content)
        }
        return Uri.fromFile(file).toString()
    }

    private fun isDownloadableMediaContent(content: String): Boolean {
        val urls = if (content.isJsonArray()) {
            GSON.fromJsonArray<String>(content).getOrNull().orEmpty()
        } else {
            listOf(content)
        }
        return urls.isNotEmpty() && urls.all {
            val scheme = Uri.parse(it).scheme
            scheme.equals("http", true) ||
                scheme.equals("https", true) ||
                (scheme.equals("file", true) && isVideoManifestUrl(it))
        }
    }

    private fun isVideoManifestUrl(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".ism")
    }

    private fun Book.cacheGroupKey(mode: CacheManageMode): String {
        return listOf(
            mode.name,
            name.trim(),
            getRealAuthor().trim()
        ).joinToString(separator = "\u001F")
    }

    private fun Book.cacheSourceKey(): String {
        return listOf(
            origin.ifBlank { originName },
            bookUrl
        ).joinToString(separator = "\u001F")
    }

    private fun Book.cacheSourceName(): String {
        return when {
            isLocal -> context.getString(R.string.local)
            originName.isNotBlank() -> originName
            origin.isNotBlank() -> origin
            else -> context.getString(R.string.unknown)
        }
    }

    private fun CacheBookItem.toSourceVariant(): CacheBookSourceVariant {
        return CacheBookSourceVariant(
            sourceKey = sourceKey,
            sourceName = sourceName,
            book = book,
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            storageSizeBytes = storageSizeBytes,
            storageSummary = storageSummary,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = inBookshelf,
            sourceAvailable = sourceAvailable
        )
    }
}

private data class CacheBookStorage(
    val totalBytes: Long,
    val displayText: String
)

enum class CacheManageMode(@StringRes val titleRes: Int, val bookType: Int) {
    BOOK(R.string.cache_manage_books, BookType.text),
    AUDIO(R.string.cache_manage_audio, BookType.audio),
    VIDEO(R.string.cache_manage_video, BookType.video),
    MANGA(R.string.cache_manage_manga, BookType.image)
}

enum class CacheChapterFilter {
    ALL,
    CACHED,
    UNCACHED
}

data class CacheBookItem(
    val book: Book,
    val mode: CacheManageMode,
    val groupKey: String,
    val sourceKey: String,
    val sourceName: String,
    val cachedCount: Int,
    val totalChapterCount: Int,
    val storageSizeBytes: Long = 0L,
    val storageSummary: String = "",
    val taskState: AudioCacheTaskState? = null,
    val manifest: CacheBookManifest? = null,
    val inBookshelf: Boolean = true,
    val sourceAvailable: Boolean = true,
    val sourceVariants: List<CacheBookSourceVariant> = emptyList()
)

data class CacheBookSourceVariant(
    val sourceKey: String,
    val sourceName: String,
    val book: Book,
    val cachedCount: Int,
    val totalChapterCount: Int,
    val storageSizeBytes: Long = 0L,
    val storageSummary: String = "",
    val taskState: AudioCacheTaskState? = null,
    val manifest: CacheBookManifest? = null,
    val inBookshelf: Boolean = true,
    val sourceAvailable: Boolean = true
)

data class CacheChapterItem(
    val chapter: BookChapter,
    val cached: Boolean
)

data class CacheSummary(
    val bookCount: Int,
    val cachedChapterCount: Int,
    val currentModeSize: Long,
    val totalCacheSize: Long,
    val storageDetails: List<CacheStorageDetail>,
    val mode: CacheManageMode
)

data class CacheStorageDetail(
    val name: String,
    val bytes: Long,
    val deleteTarget: CacheStorageDeleteTarget? = null
)

private data class CacheStorageGroup(
    val name: String,
    val files: List<File>,
    val deleteTarget: CacheStorageDeleteTarget? = null
) {
    fun toDetail(): CacheStorageDetail {
        return CacheStorageDetail(name, files.sumOf { it.directorySize() }, deleteTarget)
    }
}

enum class CacheStorageDeleteTarget {
    COVER_THUMBS,
    DISCOVER_RSS,
    GLIDE,
    SHARE_JS,
    TTS,
    EPUB_TEMP,
    IMAGE_TEMP,
    SO_DOWNLOAD,
    AUDIO,
    VIDEO,
    PLAYER_TEMP,
    CACHE_PACKAGE,
    READ_CONFIG,
    UPLOAD,
    LOGS,
    ARCHIVE_TEMP
}

private data class MediaCacheManifest(
    val bookName: String,
    val author: String,
    val bookUrl: String,
    val chapters: List<Chapter>
) {
    data class Chapter(
        val index: Int,
        val title: String,
        val url: String,
        val resourceUrl: String?,
        val fileCount: Int
    )
}

private fun CacheBookManifest.matches(mode: CacheManageMode): Boolean {
    return type and mode.bookType > 0
}

private val CacheManageMode.isMedia: Boolean
    get() = this == CacheManageMode.AUDIO || this == CacheManageMode.VIDEO

private val Book.isMedia: Boolean
    get() = isAudio || isVideo

private val Book.cacheManageMode: CacheManageMode
    get() = if (isVideo) CacheManageMode.VIDEO else CacheManageMode.AUDIO

private fun AudioCacheTaskState?.isVisibleAudioTask(): Boolean {
    return this?.active == true || this?.status == CacheTaskStatus.PAUSED
}

private fun List<BookChapter>.filterByKey(key: String?): List<BookChapter> {
    if (key.isNullOrBlank()) return this
    return filter { it.title.contains(key, ignoreCase = true) }
}

private fun List<Int>.toRanges(): List<Pair<Int, Int>> {
    if (isEmpty()) return emptyList()
    val ranges = arrayListOf<Pair<Int, Int>>()
    var start = first()
    var previous = first()
    drop(1).forEach { value ->
        if (value == previous + 1) {
            previous = value
        } else {
            ranges.add(start to previous)
            start = value
            previous = value
        }
    }
    ranges.add(start to previous)
    return ranges
}

private fun File.fileSize(): Long {
    return if (isFile) length() else 0L
}

private fun File.directorySize(): Long {
    if (!exists()) return 0L
    if (isFile) return length()
    return listFiles()?.sumOf { it.directorySize() } ?: 0L
}

private fun File.childrenSize(excludes: Set<String> = emptySet()): Long {
    if (!isDirectory) return 0L
    return listFiles()
        ?.asSequence()
        ?.filterNot { it.name in excludes }
        ?.sumOf { it.directorySize() }
        ?: 0L
}

private fun List<CacheStorageGroup>.cacheChildPaths(root: File): Set<String> {
    val rootPath = root.absoluteFile
    return flatMap { group ->
        group.files.mapNotNull { file ->
            val parent = file.parentFile?.absoluteFile ?: return@mapNotNull null
            if (parent == rootPath) file.name else null
        }
    }.toSet()
}

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.getDefault(), "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.getDefault(), "%.2f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
