package dev.lokesh.shop.product.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByCategoryId(Long categoryId, Pageable pageable);

    // Two-step listing (step 8, design D21): page over ids only, then fetch those rows whole.
    // JOIN FETCH of a collection can't be combined with Pageable (Hibernate would page in memory,
    // HHH90003004), which is why the ids are paged first.

    @Query(value = "select p.id from Product p where p.category.id = :categoryId",
            countQuery = "select count(p) from Product p where p.category.id = :categoryId")
    Page<Long> findIdsByCategoryId(Long categoryId, Pageable pageable);

    @Query(value = "select p.id from Product p", countQuery = "select count(p) from Product p")
    Page<Long> findAllIds(Pageable pageable);

    @Query("select distinct p from Product p join fetch p.category left join fetch p.images where p.id in :ids")
    List<Product> findWithImagesByIdIn(Collection<Long> ids);
}
