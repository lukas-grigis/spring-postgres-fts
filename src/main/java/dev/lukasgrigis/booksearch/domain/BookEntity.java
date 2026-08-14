package dev.lukasgrigis.booksearch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "book")
public class BookEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String excerpt;

    @Column(name = "published_year")
    private Integer publishedYear;

    @Column(name = "source_url")
    private String sourceUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private AuthorEntity author;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "genre_id", nullable = false)
    private GenreEntity genre;

    // search_vector is a STORED generated column computed by Postgres from title + excerpt
    // (db/changelog/001-schema.xml, changeset 006). Mapped read-only so Specifications can
    // reference root.get("searchVector")
    // in the `fts` predicate; Postgres has no JDBC-native tsvector type, so SqlTypes.OTHER is the
    // escape hatch that lets Hibernate pass the column through opaquely instead of coercing it.
    @Column(name = "search_vector", insertable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.OTHER)
    private String searchVector;

    protected BookEntity() {
    }

    public BookEntity(
            String title,
            String excerpt,
            Integer publishedYear,
            String sourceUrl,
            AuthorEntity author,
            GenreEntity genre
    ) {
        this.title = title;
        this.excerpt = excerpt;
        this.publishedYear = publishedYear;
        this.sourceUrl = sourceUrl;
        this.author = author;
        this.genre = genre;
    }

    public Long getId() {
        return id;
    }

    public AuthorEntity getAuthor() {
        return author;
    }

}
