package com.example.data

import kotlinx.coroutines.flow.Flow

class AppRepository(private val appDao: AppDao) {
    fun getAllDigestItems(): Flow<List<DigestItem>> = appDao.getAllDigestItems()
    fun getDigestItemsByDate(date: String): Flow<List<DigestItem>> = appDao.getDigestItemsByDate(date)
    fun getDigestItemById(id: Long): Flow<DigestItem?> = appDao.getDigestItemById(id)
    suspend fun getDigestItemByIdSuspend(id: Long): DigestItem? = appDao.getDigestItemByIdSuspend(id)
    
    suspend fun insertDigestItems(items: List<DigestItem>) = appDao.insertDigestItems(items)
    suspend fun updateDigestItem(item: DigestItem) = appDao.updateDigestItem(item)
    suspend fun updateBookmarkStatus(id: Long, isBookmarked: Boolean) = appDao.updateBookmarkStatus(id, isBookmarked)
    suspend fun updateReadStatus(id: Long, isRead: Boolean) = appDao.updateReadStatus(id, isRead)
    
    fun getBookmarkedDigestItems(): Flow<List<DigestItem>> = appDao.getBookmarkedDigestItems()
    suspend fun getDigestItemsCount(): Int = appDao.getDigestItemsCount()
    suspend fun deleteAllDigestItems() = appDao.deleteAllDigestItems()
    
    fun getAllEditorialItems(): Flow<List<EditorialItem>> = appDao.getAllEditorialItems()
    fun getEditorialItemById(id: Long): Flow<EditorialItem?> = appDao.getEditorialItemById(id)
    suspend fun insertEditorialItems(items: List<EditorialItem>) = appDao.insertEditorialItems(items)
    
    fun getQuizQuestionsByDate(date: String): Flow<List<QuizQuestion>> = appDao.getQuizQuestionsByDate(date)
    suspend fun getQuizQuestionsByDateSuspend(date: String): List<QuizQuestion> = appDao.getQuizQuestionsByDateSuspend(date)
    suspend fun insertQuizQuestions(questions: List<QuizQuestion>) = appDao.insertQuizQuestions(questions)
    
    fun getQuizResultByDate(date: String): Flow<QuizResult?> = appDao.getQuizResultByDate(date)
    suspend fun getQuizResultByDateSuspend(date: String): QuizResult? = appDao.getQuizResultByDateSuspend(date)
    suspend fun insertQuizResult(result: QuizResult) = appDao.insertQuizResult(result)
    suspend fun deleteQuizResultByDate(date: String) = appDao.deleteQuizResultByDate(date)

    suspend fun getTranslation(text: String, language: String): String? {
        val hash = text.hashCode()
        return appDao.getTranslation(hash, language)?.translatedText
    }

    suspend fun saveTranslation(text: String, language: String, translatedText: String) {
        val hash = text.hashCode()
        appDao.insertTranslation(TranslationCache(
            originalTextHash = hash,
            language = language,
            originalText = text,
            translatedText = translatedText
        ))
    }
}
