package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.utils.PdfGenerator
import com.example.work.NotificationScheduler
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = AppRepository(database.appDao)
    private val preferencesManager = PreferencesManager(application)

    // Seeding state
    val isSeedingActive = MutableStateFlow(true)
    val isRefreshing = MutableStateFlow(false)

    // Settings States from DataStore
    val onboardingSeen = preferencesManager.onboardingSeenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val darkMode = preferencesManager.darkModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val themeProfile = preferencesManager.themeProfileFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Light")

    val notificationTime = preferencesManager.notificationTimeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "08:00")

    val dataSaver = preferencesManager.dataSaverFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val topicPreferences = preferencesManager.topicPreferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val selectedLanguage = preferencesManager.languageFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "English")

    // UI Category filters
    val homeCategoryFilter = MutableStateFlow<Category?>(null)
    val bookmarkCategoryFilter = MutableStateFlow<Category?>(null)

    // Current date today (used to match database dates)
    val todayDateString = DatabaseSeeder.getTodayDateString()

    // Room Database Observables
    val digestItemsFlow = repository.getAllDigestItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarkedItemsFlow = repository.getBookmarkedDigestItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val editorialItemsFlow = repository.getAllEditorialItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered lists
    val filteredHomeItems = combine(
        digestItemsFlow,
        homeCategoryFilter,
        topicPreferences
    ) { items, activeFilter, preferredTopics ->
        // 1. Filter by user's general topic preferences (if configured in settings, non-empty)
        val afterPreference = if (preferredTopics.isNotEmpty()) {
            items.filter { preferredTopics.contains(it.category.name) }
        } else {
            items
        }
        // 2. Filter by currently selected Category chip in Home
        if (activeFilter != null) {
            afterPreference.filter { it.category == activeFilter }
        } else {
            afterPreference
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredBookmarkedItems = combine(
        bookmarkedItemsFlow,
        bookmarkCategoryFilter
    ) { items, activeFilter ->
        if (activeFilter != null) {
            items.filter { it.category == activeFilter }
        } else {
            items
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Quiz States
    val quizQuestionsFlow = repository.getQuizQuestionsByDate(todayDateString)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val quizResultFlow = repository.getQuizResultByDate(todayDateString)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentQuizQuestionIndex = MutableStateFlow(0)
    val selectedAnswers = MutableStateFlow<Map<Int, Int>>(emptyMap()) // index -> selectedOption (0..3)

    // Custom PDF selected IDs list
    val selectedForPdfIds = MutableStateFlow<Set<Long>>(emptySet())

    init {
        seedDatabaseIfNeeded()
    }

    private fun seedDatabaseIfNeeded() {
        viewModelScope.launch {
            try {
                isSeedingActive.value = true
                // Try dynamic daily news synchronization first
                val success = NewsSyncManager.syncDailyNews(repository, forceRefresh = false)
                if (!success) {
                    // Fallback to local static seeder if dynamic sync failed or was skipped
                    val currentDigestCount = repository.getDigestItemsCount()
                    if (currentDigestCount < 15) {
                        repository.deleteAllDigestItems()
                        repository.insertDigestItems(DatabaseSeeder.getSeedDigestItems())
                    }
                }
                
                // Also check if quiz questions are empty
                val existingItems = repository.getQuizQuestionsByDateSuspend(todayDateString)
                if (existingItems.isEmpty()) {
                    repository.insertEditorialItems(DatabaseSeeder.getSeedEditorialItems())
                    repository.insertQuizQuestions(DatabaseSeeder.getSeedQuizQuestions())
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Ultimate local fallback to ensure a robust user experience
                try {
                    val currentDigestCount = repository.getDigestItemsCount()
                    if (currentDigestCount < 15) {
                        repository.deleteAllDigestItems()
                        repository.insertDigestItems(DatabaseSeeder.getSeedDigestItems())
                    }
                } catch (innerEx: Exception) {
                    innerEx.printStackTrace()
                }
            } finally {
                isSeedingActive.value = false
            }
        }
    }

    fun refreshNews() {
        viewModelScope.launch {
            try {
                isRefreshing.value = true
                NewsSyncManager.syncDailyNews(repository, forceRefresh = true)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(getApplication(), "Failed to refresh news feed.", Toast.LENGTH_SHORT).show()
            } finally {
                isRefreshing.value = false
            }
        }
    }

    // Toggle Bookmarks & Reads
    fun toggleBookmark(id: Long, currentStatus: Boolean) {
        viewModelScope.launch {
            repository.updateBookmarkStatus(id, !currentStatus)
        }
    }

    fun markAsRead(id: Long) {
        viewModelScope.launch {
            repository.updateReadStatus(id, true)
        }
    }

    // PDF Selection Toggle
    fun togglePdfSelection(id: Long) {
        val currentSet = selectedForPdfIds.value
        if (currentSet.contains(id)) {
            selectedForPdfIds.value = currentSet - id
        } else {
            selectedForPdfIds.value = currentSet + id
        }
    }

    // PDF Generation & Native Sharing
    fun generateAndSharePdf() {
        viewModelScope.launch {
            val itemsToPdf = if (selectedForPdfIds.value.isNotEmpty()) {
                digestItemsFlow.value.filter { selectedForPdfIds.value.contains(it.id) }
            } else {
                digestItemsFlow.value // Default to all today's items if none specifically selected
            }

            if (itemsToPdf.isEmpty()) {
                Toast.makeText(getApplication(), "No articles available to generate PDF.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val pdfFile = PdfGenerator.generateDigestPdf(
                context = getApplication(),
                dateString = todayDateString,
                items = itemsToPdf
            )

            if (pdfFile != null && pdfFile.exists()) {
                shareFile(pdfFile)
            } else {
                Toast.makeText(getApplication(), "Failed to generate PDF.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Generate and share the Premium Weekly Digest Dossier
    fun generateAndShareWeeklyPdf() {
        viewModelScope.launch {
            // Find most-read (isRead) or bookmarked items. Fall back to all if empty.
            var itemsToPdf = digestItemsFlow.value.filter { it.isRead || it.isBookmarked }
            if (itemsToPdf.isEmpty()) {
                // Take up to 7 most recent items as fallback
                itemsToPdf = digestItemsFlow.value.take(7)
            }

            if (itemsToPdf.isEmpty()) {
                Toast.makeText(getApplication(), "No analytical summaries available for Weekly Digest.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val pdfFile = PdfGenerator.generateWeeklyDigestPdf(
                context = getApplication(),
                items = itemsToPdf
            )

            if (pdfFile != null && pdfFile.exists()) {
                shareFile(pdfFile)
            } else {
                Toast.makeText(getApplication(), "Failed to compile Weekly Digest.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareFile(file: File) {
        val context = getApplication<Application>()
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Daily News SAAR - $todayDateString")
                putExtra(Intent.EXTRA_TEXT, "Here is your offline daily calm news SAAR summary.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, "Share Daily SAAR PDF").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Sharing failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    // Settings actions
    fun setOnboardingSeen(seen: Boolean) {
        viewModelScope.launch {
            preferencesManager.setOnboardingSeen(seen)
        }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setDarkMode(enabled)
        }
    }

    fun setThemeProfile(profile: String) {
        viewModelScope.launch {
            preferencesManager.setThemeProfile(profile)
        }
    }

    fun setNotificationTime(time: String) {
        viewModelScope.launch {
            preferencesManager.setNotificationTime(time)
            NotificationScheduler.scheduleDailyNotification(getApplication(), time)
        }
    }

    fun setDataSaver(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setDataSaver(enabled)
        }
    }

    fun setLanguage(language: String) {
        viewModelScope.launch {
            preferencesManager.setLanguage(language)
        }
    }

    fun translateText(text: String, language: String, onCompleted: (String) -> Unit) {
        if (language == "English" || language.isEmpty()) {
            onCompleted(text)
            return
        }

        viewModelScope.launch {
            // 1. Check local DB translation cache first
            val cached = repository.getTranslation(text, language)
            if (cached != null) {
                onCompleted(cached)
                return@launch
            }

            // 2. Fallback to local static dictionary to keep it quick and save API quota
            val staticTranslation = TranslationHelper.translate(text, language)
            if (staticTranslation != text) {
                repository.saveTranslation(text, language, staticTranslation)
                onCompleted(staticTranslation)
                return@launch
            }

            // 3. Run through translation layer API (Gemini-3.5-flash)
            val apiTranslation = com.example.utils.GeminiTranslationClient.translateText(text, language)
            if (apiTranslation != text) {
                repository.saveTranslation(text, language, apiTranslation)
            }
            onCompleted(apiTranslation)
        }
    }

    fun toggleTopicPreference(topic: String) {
        viewModelScope.launch {
            val currentSet = topicPreferences.value
            val newSet = if (currentSet.contains(topic)) {
                currentSet - topic
            } else {
                currentSet + topic
            }
            preferencesManager.setTopicPreferences(newSet)
        }
    }

    // Quiz logic
    fun selectQuizAnswer(questionIndex: Int, optionIndex: Int) {
        val currentAnswers = selectedAnswers.value.toMutableMap()
        currentAnswers[questionIndex] = optionIndex
        selectedAnswers.value = currentAnswers
    }

    fun submitQuiz() {
        viewModelScope.launch {
            val questions = quizQuestionsFlow.value
            val answers = selectedAnswers.value
            if (questions.isEmpty()) return@launch

            var score = 0
            for (i in questions.indices) {
                val selected = answers[i]
                if (selected != null && selected == questions[i].correctOptionIndex) {
                    score++
                }
            }

            val result = QuizResult(
                date = todayDateString,
                score = score,
                totalQuestions = questions.size
            )
            repository.insertQuizResult(result)
        }
    }

    fun resetQuiz() {
        currentQuizQuestionIndex.value = 0
        selectedAnswers.value = emptyMap()
        viewModelScope.launch {
            repository.deleteQuizResultByDate(todayDateString)
        }
    }
}
