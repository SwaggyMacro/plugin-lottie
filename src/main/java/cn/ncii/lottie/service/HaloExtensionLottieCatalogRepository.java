package cn.ncii.lottie.service;

import cn.ncii.lottie.extension.LottieAnimation;
import cn.ncii.lottie.extension.LottieGroup;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/** Persists the catalog in Halo Custom Extensions. */
@Repository
public class HaloExtensionLottieCatalogRepository implements LottieCatalogRepository {

    private static final ListOptions ALL = ListOptions.builder().build();

    private final ReactiveExtensionClient client;

    public HaloExtensionLottieCatalogRepository(ReactiveExtensionClient client) {
        this.client = client;
    }

    @Override
    public Flux<LottieAnimation> listAnimations() {
        return client.listAll(LottieAnimation.class, ALL, Sort.by(Sort.Order.asc("metadata.name")));
    }

    @Override
    public Flux<LottieGroup> listGroups() {
        // Sort only by the built-in metadata name in the storage query.  Custom
        // spec fields are not indexed by default and Halo's index query engine
        // returns a 500 when asked to order by `spec.sort`/`spec.displayName`.
        // Apply the user-facing order after loading the (usually small) group
        // catalog in memory.
        return client.listAll(LottieGroup.class, ALL, Sort.by(Sort.Order.asc("metadata.name")))
            .sort(java.util.Comparator
                .comparing((LottieGroup group) -> group.getSpec() == null
                    || group.getSpec().getSort() == null ? 0 : group.getSpec().getSort())
                .thenComparing(group -> group.getSpec() == null
                    || group.getSpec().getDisplayName() == null
                    ? "" : group.getSpec().getDisplayName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(group -> group.getMetadata() == null
                    || group.getMetadata().getName() == null ? "" : group.getMetadata().getName()));
    }

    @Override
    public Mono<LottieAnimation> findAnimation(String name) {
        return client.fetch(LottieAnimation.class, name);
    }

    @Override
    public Mono<LottieGroup> findGroup(String name) {
        return client.fetch(LottieGroup.class, name);
    }

    @Override
    public Mono<LottieAnimation> saveAnimation(LottieAnimation animation) {
        return client.fetch(LottieAnimation.class, animation.getMetadata().getName())
            .flatMap(current -> {
                animation.setMetadata(current.getMetadata());
                return client.update(animation);
            })
            .switchIfEmpty(Mono.defer(() -> client.create(animation)));
    }

    @Override
    public Mono<LottieGroup> saveGroup(LottieGroup group) {
        return client.fetch(LottieGroup.class, group.getMetadata().getName())
            .flatMap(current -> {
                group.setMetadata(current.getMetadata());
                return client.update(group);
            })
            .switchIfEmpty(Mono.defer(() -> client.create(group)));
    }

    @Override
    public Mono<Void> deleteAnimation(String name) {
        return findAnimation(name).flatMap(client::delete).then();
    }

    @Override
    public Mono<Void> deleteGroup(String name) {
        return findGroup(name).flatMap(client::delete).then();
    }
}
