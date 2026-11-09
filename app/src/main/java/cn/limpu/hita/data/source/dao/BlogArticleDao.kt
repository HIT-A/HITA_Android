package cn.limpu.hita.data.source.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cn.limpu.hita.data.model.blog.BlogArticle

@Dao
interface BlogArticleDao {
    @Query("SELECT * FROM blog_article ORDER BY pubDateMillis DESC")
    fun observeAll(): LiveData<List<BlogArticle>>

    @Query("SELECT * FROM blog_article ORDER BY pubDateMillis DESC")
    fun getAll(): List<BlogArticle>

    /** Only identities are needed for unread bookkeeping; `getAll` also loads full article HTML. */
    @Query("SELECT guid FROM blog_article")
    fun getAllGuids(): List<String>

    @Query("SELECT count(*) FROM blog_article")
    fun count(): Int

    @Query("SELECT * FROM blog_article WHERE guid = :guid LIMIT 1")
    fun getByGuid(guid: String): BlogArticle?

    @Query("SELECT * FROM blog_article WHERE link = :link LIMIT 1")
    fun getByLink(link: String): BlogArticle?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(articles: List<BlogArticle>)

    @Query("DELETE FROM blog_article")
    fun deleteAll()

    @Transaction
    fun replaceAll(articles: List<BlogArticle>) {
        deleteAll()
        if (articles.isNotEmpty()) {
            upsertAll(articles)
        }
    }
}
