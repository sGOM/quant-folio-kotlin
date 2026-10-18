package com.quantfolio.domain.news

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@Entity
@Table(name = "news_articles")
class NewsArticle(
    @Column(nullable = false) var source: String,
    @Column(nullable = false) var title: String,
    @Column(nullable = false, unique = true) var url: String,
    var summary: String? = null,
    @Column(nullable = false) var publishedAt: Instant,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
)

@Entity
@Table(name = "news_article_symbols")
class NewsArticleSymbol(
    @Column(nullable = false) var articleId: Int,
    @Column(nullable = false) var symbol: String,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
)

interface NewsArticleRepository : JpaRepository<NewsArticle, Int>

interface NewsArticleSymbolRepository : JpaRepository<NewsArticleSymbol, Int>
