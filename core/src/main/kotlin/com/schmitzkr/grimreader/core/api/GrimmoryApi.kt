package com.schmitzkr.grimreader.core.api

import com.schmitzkr.grimreader.core.model.Author
import com.schmitzkr.grimreader.core.model.AudiobookInfo
import com.schmitzkr.grimreader.core.model.AuthTokens
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.Bookmark
import com.schmitzkr.grimreader.core.model.CurrentUser
import com.schmitzkr.grimreader.core.model.FilterOptions
import com.schmitzkr.grimreader.core.model.Library
import com.schmitzkr.grimreader.core.model.MagicShelf
import com.schmitzkr.grimreader.core.model.PageResponse
import com.schmitzkr.grimreader.core.model.PublicSettings
import com.schmitzkr.grimreader.core.model.Series
import com.schmitzkr.grimreader.core.model.Shelf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Grimmory's `/api/v1` surface, as this app uses it. Paths are relative to
 * `<server>/api/v1/`. Comma-joined list values (`status`, `fileType`) are
 * deliberate: Spring splits them itself, and the bracketed form some
 * clients send is not something the server's record binder accepts.
 */
interface GrimmoryApi {

    // ── Auth ──────────────────────────────────────────────────────────────

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthTokens

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthTokens

    /** Revokes every token of the user, other devices included. */
    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<Unit>

    @GET("auth/oidc/state")
    suspend fun oidcState(): JsonObject

    @POST("auth/oidc/callback")
    suspend fun oidcCallback(@Body body: OidcCallbackRequest): AuthTokens

    @GET("public-settings")
    suspend fun publicSettings(): PublicSettings

    @GET("users/me")
    suspend fun currentUser(): CurrentUser

    // ── Libraries and books ───────────────────────────────────────────────

    @GET("libraries")
    suspend fun libraries(): List<Library>

    @GET("app/books")
    suspend fun books(
        @Query("libraryId") libraryId: Long? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
        @Query("sort") sort: String? = null,
        @Query("dir") dir: String? = null,
        @Query("authors") authors: List<String>? = null,
        @Query("fileType") fileType: String? = null,
        @Query("status") status: String? = null,
    ): PageResponse<Book>

    @GET("app/books/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50,
    ): PageResponse<Book>

    @GET("app/books/recently-added")
    suspend fun recentlyAdded(@Query("limit") limit: Int = 10): List<Book>

    @GET("app/books/random")
    suspend fun randomBooks(
        @Query("size") size: Int = 20,
        @Query("libraryId") libraryId: Long? = null,
    ): List<Book>

    @GET("app/books/{id}")
    suspend fun book(@Path("id") id: Long): Book

    @GET("app/filter-options")
    suspend fun filterOptions(@Query("libraryId") libraryId: Long? = null): FilterOptions

    @PUT("app/books/{id}/status")
    suspend fun updateReadStatus(@Path("id") id: Long, @Body body: StatusRequest): Response<Unit>

    @PUT("app/books/{id}/rating")
    suspend fun updatePersonalRating(@Path("id") id: Long, @Body body: RatingRequest): Response<Unit>

    // ── Progress ──────────────────────────────────────────────────────────

    @GET("app/books/{id}/progress")
    suspend fun progress(@Path("id") id: Long): Response<JsonObject>

    @PUT("app/books/{id}/progress")
    suspend fun updateProgress(@Path("id") id: Long, @Body body: JsonObject): Response<Unit>

    // ── Audiobooks ────────────────────────────────────────────────────────

    @GET("audiobooks/{id}/info")
    suspend fun audiobookInfo(@Path("id") id: Long): AudiobookInfo

    // ── Comics ────────────────────────────────────────────────────────────

    @GET("cbx/{id}/pages")
    suspend fun comicPages(@Path("id") id: Long): List<Int>

    // ── Browse ────────────────────────────────────────────────────────────

    @GET("app/series")
    suspend fun series(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
    ): PageResponse<Series>

    @GET("app/series/{name}/books")
    suspend fun seriesBooks(
        @Path("name") name: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
    ): PageResponse<Book>

    @GET("app/authors")
    suspend fun authors(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
    ): PageResponse<Author>

    @GET("app/authors/{id}")
    suspend fun author(@Path("id") id: Long): Author

    @GET("app/shelves")
    suspend fun shelves(): List<Shelf>

    @GET("app/shelves/magic")
    suspend fun magicShelves(): List<MagicShelf>

    @GET("app/shelves/magic/{id}/books")
    suspend fun magicShelfBooks(
        @Path("id") id: Long,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
    ): PageResponse<Book>

    // ── Bookmarks ─────────────────────────────────────────────────────────

    @GET("bookmarks/book/{id}")
    suspend fun bookmarks(@Path("id") bookId: Long): List<Bookmark>

    @POST("bookmarks")
    suspend fun createBookmark(@Body body: JsonObject): Bookmark

    @PUT("bookmarks/{id}")
    suspend fun updateBookmark(@Path("id") id: Long, @Body body: JsonObject): Response<Unit>

    @DELETE("bookmarks/{id}")
    suspend fun deleteBookmark(@Path("id") id: Long): Response<Unit>

    // ── Sessions and stats ────────────────────────────────────────────────

    @POST("reading-sessions")
    suspend fun createReadingSession(@Body body: JsonObject): Response<Unit>

    @GET("user-stats/reading/streak")
    suspend fun readingStreak(): JsonObject

    @GET("user-stats/reading/timeline")
    suspend fun weekTimeline(@Query("year") year: Int, @Query("week") week: Int): List<JsonObject>

    @GET("user-stats/listening/completion")
    suspend fun listeningCompletion(): JsonObject

    @GET("user-stats/listening/heatmap/monthly")
    suspend fun listeningDays(@Query("year") year: Int, @Query("month") month: Int): List<JsonObject>

    // ── Files ─────────────────────────────────────────────────────────────

    @Streaming
    @GET("books/{id}/download")
    suspend fun downloadBook(@Path("id") id: Long): Response<ResponseBody>

    @Streaming
    @GET("books/{id}/files/{fileId}/download")
    suspend fun downloadBookFile(
        @Path("id") id: Long,
        @Path("fileId") fileId: Long,
    ): Response<ResponseBody>
}

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

/** What `POST /auth/oidc/callback` takes; the server redeems the code itself. */
@Serializable
data class OidcCallbackRequest(
    val code: String,
    val state: String,
    val redirectUri: String,
    val codeVerifier: String,
    val nonce: String,
)

@Serializable
data class StatusRequest(val status: String)

@Serializable
data class RatingRequest(val rating: Int)
