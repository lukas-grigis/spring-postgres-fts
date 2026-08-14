package dev.lukasgrigis.booksearch.search;

import dev.lukasgrigis.booksearch.domain.GenreEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GenreRepository extends JpaRepository<GenreEntity, Long> {

    @Query("select g.name from GenreEntity g order by g.name")
    List<String> findAllNamesOrderByName();

}
