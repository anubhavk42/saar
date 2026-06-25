package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "digest_items")
data class DigestItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: Category,
    val headline: String,
    val previewText: String,
    val contextText: String,
    val keyPointsText: String,
    val whyItMattersText: String,
    val examAngleText: String,
    val sourceAFraming: String,
    val sourceBFraming: String,
    val sourceCFraming: String,
    val date: String, // format YYYY-MM-DD
    val isBookmarked: Boolean = false,
    val isRead: Boolean = false,
    val isDailyPulse: Boolean = false
)

@Entity(tableName = "editorial_items")
data class EditorialItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val takeawayText: String,
    val fullAnalysisText: String,
    val date: String // format YYYY-MM-DD
)

@Entity(tableName = "quiz_questions")
data class QuizQuestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String, // format YYYY-MM-DD
    val questionText: String,
    val option1: String,
    val option2: String,
    val option3: String,
    val option4: String,
    val correctOptionIndex: Int
)

@Entity(tableName = "quiz_results")
data class QuizResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String, // format YYYY-MM-DD
    val score: Int,
    val totalQuestions: Int
)

@Entity(tableName = "translation_cache", primaryKeys = ["originalTextHash", "language"])
data class TranslationCache(
    val originalTextHash: Int,
    val language: String,
    val originalText: String,
    val translatedText: String
)
