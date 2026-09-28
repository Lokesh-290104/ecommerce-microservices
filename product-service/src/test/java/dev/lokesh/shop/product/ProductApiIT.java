package dev.lokesh.shop.product;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The catalog API end to end against real MySQL (Flyway schema, inventory row, optimistic lock). */
@Testcontainers(disabledWithoutDocker = true)
class ProductApiIT extends MySqlTestSupport {

    // Seeded by V2__seed_categories.sql.
    private static final long ELECTRONICS = 1;
    private static final long BOOKS = 2;
    /** Any signed-in user may change the catalog (design D23); reading it is public. */
    private static final String USER = TestTokens.bearer(TestTokens.user(1));

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthIsUpWithMigratedSchema() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM categories", Integer.class)).isGreaterThanOrEqualTo(5);
    }

    @Test
    void createReturnsTheProductWithStockAndOrderedImages() throws Exception {
        String json = mvc.perform(post("/api/products").header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(ELECTRONICS, "Noise-cancelling headphones", "199.99", 25,
                                "\"https://img.example.com/a.jpg\",\"https://img.example.com/b.jpg\"")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("http://localhost/api/products/")))
                .andExpect(jsonPath("$.categoryId").value(ELECTRONICS))
                .andExpect(jsonPath("$.categoryName").value("Electronics"))
                .andExpect(jsonPath("$.price").value(199.99))
                .andExpect(jsonPath("$.available").value(25))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.imageUrls", contains("https://img.example.com/a.jpg", "https://img.example.com/b.jpg")))
                .andReturn().getResponse().getContentAsString();
        long id = id(json);

        assertThat(jdbc.queryForMap("SELECT on_hand, reserved FROM inventory WHERE product_id = ?", id))
                .containsEntry("on_hand", 25).containsEntry("reserved", 0);
        mvc.perform(get("/api/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Noise-cancelling headphones"))
                .andExpect(jsonPath("$.available").value(25));
    }

    @Test
    void availableIsOnHandMinusReserved() throws Exception {
        long id = create(BOOKS, "A book", "12.50", 10);
        jdbc.update("UPDATE inventory SET reserved = 3 WHERE product_id = ?", id);

        mvc.perform(get("/api/products/{id}", id)).andExpect(jsonPath("$.available").value(7));
    }

    @Test
    void unknownCategoryIs422AndUnknownProductIs404() throws Exception {
        mvc.perform(post("/api/products").header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(9_999, "Orphan", "1.00", 1, "")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        mvc.perform(get("/api/products/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void listFiltersByCategoryAndIsSortedById() throws Exception {
        long e1 = create(ELECTRONICS, "Phone", "499.00", 5);
        long b1 = create(BOOKS, "Novel", "9.99", 5);
        long e2 = create(ELECTRONICS, "Tablet", "299.00", 5);

        String json = mvc.perform(get("/api/products").param("categoryId", String.valueOf(ELECTRONICS))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Integer> ids = JsonPath.read(json, "$.content[*].id");
        List<Integer> categoryIds = JsonPath.read(json, "$.content[*].categoryId");
        assertThat(ids).contains((int) e1, (int) e2).doesNotContain((int) b1).isSorted();
        assertThat(categoryIds).containsOnly((int) ELECTRONICS);

        mvc.perform(get("/api/products").param("size", "1"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.size").value(1));
    }

    @Test
    void updateWithTheCurrentVersionReplacesCatalogFieldsButNotStock() throws Exception {
        long id = create(ELECTRONICS, "Old name", "10.00", 8);

        mvc.perform(put("/api/products/{id}", id).header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(BOOKS, "New name", "12.00", "\"https://img.example.com/new.jpg\"", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New name"))
                .andExpect(jsonPath("$.categoryId").value(BOOKS))
                .andExpect(jsonPath("$.price").value(12.00))
                .andExpect(jsonPath("$.imageUrls", contains("https://img.example.com/new.jpg")))
                .andExpect(jsonPath("$.available").value(8))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_images WHERE product_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void updateWithAStaleVersionIs409AndChangesNothing() throws Exception {
        long id = create(ELECTRONICS, "Contested", "10.00", 1);
        mvc.perform(put("/api/products/{id}", id).header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(ELECTRONICS, "First writer", "11.00", "", 0)))
                .andExpect(status().isOk());

        mvc.perform(put("/api/products/{id}", id).header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(ELECTRONICS, "Second writer", "12.00", "", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mvc.perform(get("/api/products/{id}", id))
                .andExpect(jsonPath("$.name").value("First writer"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void updateThatOnlyChangesImagesStillBumpsTheVersion() throws Exception {
        long id = create(ELECTRONICS, "Camera", "250.00", 2);

        mvc.perform(put("/api/products/{id}", id).header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(ELECTRONICS, "Camera", "250.00", "\"https://img.example.com/c.jpg\"", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void readingIsPublicButChangingNeedsAToken() throws Exception {
        long id = create(BOOKS, "Public book", "5.00", 1);

        mvc.perform(get("/api/products/{id}", id)).andExpect(status().isOk());
        mvc.perform(get("/api/products").param("categoryId", String.valueOf(BOOKS))).andExpect(status().isOk());
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(BOOKS, "Anonymous", "1.00", 1, "")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(put("/api/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(BOOKS, "Anonymous", "1.00", "", 0)))
                .andExpect(status().isUnauthorized());
    }

    private long create(long categoryId, String name, String price, int stock) throws Exception {
        String json = mvc.perform(post("/api/products").header(HttpHeaders.AUTHORIZATION, USER).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(categoryId, name, price, stock, "")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return id(json);
    }

    private static String createBody(long categoryId, String name, String price, int stock, String imageUrls) {
        return "{\"categoryId\":" + categoryId + ",\"name\":\"" + name
                + "\",\"price\":" + price + ",\"initialStock\":" + stock + ",\"imageUrls\":[" + imageUrls + "]}";
    }

    private static String updateBody(long categoryId, String name, String price, String imageUrls, long version) {
        return "{\"categoryId\":" + categoryId + ",\"name\":\"" + name + "\",\"price\":" + price
                + ",\"imageUrls\":[" + imageUrls + "],\"version\":" + version + "}";
    }

    private static long id(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }
}
