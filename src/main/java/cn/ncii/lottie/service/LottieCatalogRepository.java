package cn.ncii.lottie.service;

import cn.ncii.lottie.extension.LottieAnimation;
import cn.ncii.lottie.extension.LottieGroup;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Storage boundary for the Lottie catalog. */
public interface LottieCatalogRepository {

    Flux<LottieAnimation> listAnimations();

    Flux<LottieGroup> listGroups();

    Mono<LottieAnimation> findAnimation(String name);

    Mono<LottieGroup> findGroup(String name);

    Mono<LottieAnimation> saveAnimation(LottieAnimation animation);

    Mono<LottieGroup> saveGroup(LottieGroup group);

    Mono<Void> deleteGroup(String name);

    Mono<Void> deleteAnimation(String name);
}
