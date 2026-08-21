package de.mhus.hrafnagud.munin.article;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** MongoDB repository for {@link ArticleContentDocument}. Package-private. */
interface ArticleContentRepository extends MongoRepository<ArticleContentDocument, String> {

    Optional<ArticleContentDocument> findByArticleId(String articleId);

    void deleteByArticleId(String articleId);
}
