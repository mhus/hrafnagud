package de.mhus.hrafnagud.munin.article;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** MongoDB repository for {@link ArticleContentDocument}. Package-private. */
interface ArticleContentRepository extends MongoRepository<ArticleContentDocument, String> {

    Optional<ArticleContentDocument> findByArticleId(String articleId);

    List<ArticleContentDocument> findByArticleIdIn(Collection<String> articleIds);

    void deleteByArticleId(String articleId);
}
