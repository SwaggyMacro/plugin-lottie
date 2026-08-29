package cn.ncii.lottie.web;

import cn.ncii.lottie.extension.LottieAnimation;
import cn.ncii.lottie.extension.LottieConfig;
import cn.ncii.lottie.extension.LottieGroup;
import cn.ncii.lottie.service.LottieCatalogService;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import run.halo.app.plugin.ApiVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import org.springframework.data.domain.Sort;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.attachment.Group;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@RestController
@ApiVersion("console.api.lottie.halo.run/v1alpha1")
@RequestMapping
public class LottieController {

    private static final int MAX_UPLOAD_BYTES = 200 * 1024 * 1024;

    private final LottieCatalogService catalog;
    private final ReactiveExtensionClient extensionClient;

    public LottieController(LottieCatalogService catalog, ReactiveExtensionClient extensionClient) {
        this.catalog = catalog;
        this.extensionClient = extensionClient;
    }

    @GetMapping("/animations")
    public Mono<List<LottieAnimation>> animations(
        @RequestParam(name = "group", required = false) String group) {
        return catalog.listAnimations(group).collectList();
    }

    @GetMapping("/animations/{name}")
    public Mono<LottieAnimation> animation(@PathVariable("name") String name) {
        return catalog.getAnimation(name);
    }

    @PostMapping("/animations")
    public Mono<LottieAnimation> saveAnimation(@RequestBody AnimationRequest request) {
        return catalog.saveAnimation(request.name(), request.displayName(), request.groupName(), request.format(),
            request.mediaType(), request.attachmentName(), request.attachmentUrl(), request.sha256(),
            request.defaults(), request.sourceFileName(), request.enabled(), request.tags());
    }

    /** Explicit update route for clients that prefer REST semantics. */
    @PutMapping("/animations/{name}")
    public Mono<LottieAnimation> updateAnimation(@PathVariable("name") String name,
                                                  @RequestBody AnimationRequest request) {
        return catalog.saveAnimation(name, request.displayName(), request.groupName(), request.format(),
            request.mediaType(), request.attachmentName(), request.attachmentUrl(), request.sha256(),
            request.defaults(), request.sourceFileName(), request.enabled(), request.tags());
    }

    @DeleteMapping("/animations/{name}")
    public Mono<Void> deleteAnimation(@PathVariable("name") String name,
                                       @RequestParam(name = "deleteAttachment", defaultValue = "false")
                                       boolean deleteAttachment) {
        return catalog.deleteAnimation(name, deleteAttachment);
    }

    @PostMapping("/animations/bulk-delete")
    public Mono<Void> bulkDelete(@RequestBody BulkRequest request) {
        return catalog.bulkDelete(request.names(), Boolean.TRUE.equals(request.deleteAttachment()));
    }

    @PostMapping("/animations/bulk-move")
    public Mono<Void> bulkMove(@RequestBody BulkMoveRequest request) {
        return catalog.bulkMove(request.names(), request.groupName());
    }

    @DeleteMapping("/groups/{name}")
    public Mono<Void> deleteGroup(@PathVariable("name") String name) {
        return catalog.deleteGroup(name);
    }

    @GetMapping("/groups")
    public Mono<List<LottieGroup>> groups() {
        return catalog.listGroups().collectList();
    }

    @GetMapping("/settings")
    public Mono<LottieCatalogService.EffectiveSettings> settings() {
        return catalog.effectiveSettings();
    }

    /**
     * Reads Halo attachment groups through the core extension client.  The
     * storage HTTP endpoint varies between Halo minor versions, while the
     * extension contract remains stable for plugins.
     */
    @GetMapping("/attachment-groups")
    public Mono<List<AttachmentGroup>> attachmentGroups() {
        /*
         * Query the official storage Group extension instead of the storage
         * HTTP endpoint.  The latter is version-sensitive and can return 500
         * when a policy or index is unavailable.  Counts are calculated in
         * memory because attachment group names are not guaranteed indexed.
         */
        Mono<Map<String, Long>> counts = extensionClient
            .listAll(Attachment.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.asc("metadata.name")))
            .map(Attachment::getSpec)
            .filter(spec -> spec != null && spec.getGroupName() != null && !spec.getGroupName().isBlank())
            .collectMultimap(spec -> spec.getGroupName())
            .map(groups -> groups.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                    entry -> (long) entry.getValue().size())));
        return extensionClient.listAll(Group.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.asc("metadata.name")))
            .collectList()
            .zipWith(counts.onErrorReturn(Map.of()), (groups, groupedCounts) -> groups.stream()
                .map(group -> {
                    String name = group.getMetadata() == null || group.getMetadata().getName() == null
                        ? "" : group.getMetadata().getName();
                    String displayName = group.getSpec() == null
                        ? null : group.getSpec().getDisplayName();
                    if (displayName == null || displayName.isBlank()) {
                        displayName = name;
                    }
                    return new AttachmentGroup(name, displayName,
                        groupedCounts.getOrDefault(name, 0L));
                })
                .filter(group -> !group.name().isBlank())
                .toList())
            .onErrorReturn(List.of());
    }

    /** Lists real Halo attachment policies so clients can diagnose storage setup. */
    @GetMapping("/attachment-policies")
    public Mono<List<AttachmentPolicy>> attachmentPolicies() {
        return extensionClient.listAll(Policy.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.asc("metadata.name")))
            .map(policy -> new AttachmentPolicy(
                policy.getMetadata() == null ? "" : policy.getMetadata().getName(),
                policy.getSpec() == null ? null : policy.getSpec().getDisplayName()))
            .filter(policy -> !policy.name().isBlank())
            .collectList()
            .onErrorReturn(List.of());
    }

    /** Returns a filtered page of Halo attachments for the native picker UI. */
    @GetMapping("/attachments")
    public Mono<AttachmentPage> attachments(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "48") int size,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "groupName", required = false) String groupName) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String query = keyword == null ? "" : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        String group = groupName == null ? "" : groupName.trim();
        return extensionClient.listAll(Attachment.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.desc("metadata.creationTimestamp")))
            .filter(item -> item.getSpec() != null
                && (group.isBlank() || group.equals(item.getSpec().getGroupName())))
            .filter(item -> query.isBlank() || attachmentMatches(item, query))
            .filter(LottieController::isAnimationAttachment)
            .collectList()
            .map(items -> {
                int from = Math.min(safePage * safeSize, items.size());
                int to = Math.min(from + safeSize, items.size());
                return new AttachmentPage(items.subList(from, to), items.size(), safePage, safeSize);
            });
    }

    /**
     * Resolves values returned by Halo's FormKit attachment input back to the
     * canonical Attachment extensions. The input can return a permalink,
     * display name, or metadata name depending on the Console version.
     */
    @PostMapping("/attachments/resolve")
    public Mono<List<Attachment>> resolveAttachments(@RequestBody AttachmentResolveRequest request) {
        Set<String> references = new LinkedHashSet<>();
        if (request != null && request.references() != null) {
            request.references().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(references::add);
        }
        if (references.isEmpty()) {
            return Mono.just(List.of());
        }
        return extensionClient.listAll(Attachment.class, ListOptions.builder().build(),
                Sort.by(Sort.Order.desc("metadata.creationTimestamp")))
            .filter(attachment -> references.stream().anyMatch(reference ->
                attachmentReferenceMatches(attachment, reference)))
            .collectList();
    }

    private static boolean attachmentReferenceMatches(Attachment attachment, String reference) {
        if (attachment == null) return false;
        String metadataName = attachment.getMetadata() == null
            ? null : attachment.getMetadata().getName();
        String displayName = attachment.getSpec() == null
            ? null : attachment.getSpec().getDisplayName();
        String permalink = attachment.getStatus() == null
            ? null : attachment.getStatus().getPermalink();
        return reference.equals(metadataName)
            || reference.equals(displayName)
            || reference.equals(permalink);
    }

    private static boolean attachmentMatches(Attachment item, String query) {
        String name = item.getMetadata() == null ? "" : item.getMetadata().getName();
        String display = item.getSpec() == null ? "" : item.getSpec().getDisplayName();
        return (name + " " + display).toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    private static boolean isAnimationAttachment(Attachment item) {
        if (item.getSpec() == null) return false;
        String name = item.getSpec().getDisplayName();
        String mediaType = item.getSpec().getMediaType();
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".lottie") || lower.endsWith(".tgs")
            || "application/json".equalsIgnoreCase(mediaType)
            || "application/gzip".equalsIgnoreCase(mediaType)
            || ("application/octet-stream".equalsIgnoreCase(mediaType)
                && lower.endsWith(".lottie"));
    }

    @PostMapping("/groups")
    public Mono<LottieGroup> saveGroup(@RequestBody GroupRequest request) {
        return catalog.saveGroup(request.name(), request.displayName(), request.parentName(),
            request.description(), request.sort());
    }

    /**
     * Preview uploaded files before committing them to the catalog.
     *
     * <p>The console proxy can normalize multipart content types (including
     * the boundary parameter). Leaving the mapping unconstrained keeps the
     * route available for both direct and proxied multipart requests; the
     * {@link RequestPart} argument still enforces the multipart contract.
     */
    @PostMapping(path = {"/import/preview", "/animations/import/preview"},
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<LottieCatalogService.ImportCandidate>> preview(@RequestPart("file") Flux<FilePart> files) {
        return files.concatMap(file -> read(file).map(bytes -> {
            try {
                String filename = file.filename() == null ? "animation.zip" : file.filename();
                if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    return List.of(catalog.normalizeUpload(filename, bytes));
                }
                return catalog.previewZip(bytes);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Unable to read archive", exception);
            }
        })).flatMapIterable(item -> item).collectList();
    }

    /** Commits uploaded animation files after the optional preview step. */
    @PostMapping(path = {"/import", "/animations/import"},
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<LottieAnimation>> importFile(@RequestPart("file") Flux<FilePart> files,
                                                  @RequestParam(name = "duplicateMode", defaultValue = "skip")
                                                  String duplicateMode,
                                                  @RequestParam(name = "groupName", required = false)
                                                  String groupName,
                                                  @RequestParam(name = "attachmentGroup", required = false)
                                                  String attachmentGroup,
                                                  @RequestParam(name = "attachmentPolicy", required = false)
                                                  String attachmentPolicy) {
        return files.concatMap(file -> read(file).flatMapMany(bytes -> {
            try {
                String filename = file.filename() == null ? "animation.json" : file.filename();
                if (filename.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                    return catalog.importZip(bytes, duplicateMode, groupName, attachmentGroup, attachmentPolicy)
                        .flatMapMany(Flux::fromIterable);
                }
                LottieCatalogService.ImportCandidate candidate = catalog.normalizeUpload(filename, bytes);
                if (groupName != null && !groupName.isBlank()) {
                    candidate = candidate.withGroupName(groupName);
                }
                return catalog.importCandidate(candidate, duplicateMode,
                    attachmentGroup, attachmentPolicy).flux();
            } catch (IOException exception) {
                return Flux.error(new IllegalArgumentException("Unable to import animation", exception));
            }
        })).collectList();
    }

    private Mono<byte[]> read(FilePart file) {
        long contentLength = file.headers().getContentLength();
        if (contentLength > MAX_UPLOAD_BYTES) {
            return Mono.error(new IllegalArgumentException("Animation file exceeds 200MB"));
        }
        return DataBufferUtils.join(file.content(), MAX_UPLOAD_BYTES)
            .map(buffer -> {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return bytes;
            } finally {
                DataBufferUtils.release(buffer);
            }
            })
            .onErrorMap(DataBufferLimitException.class,
                error -> new IllegalArgumentException("Animation file exceeds 200MB", error));
    }

    public record AnimationRequest(String name, String displayName, String groupName, String format,
                                   String mediaType, String attachmentName, String attachmentUrl, String sha256,
                                   LottieConfig defaults, String sourceFileName, Boolean enabled, List<String> tags) {}
    public record GroupRequest(String name, String displayName, String parentName, String description,
                               Integer sort) {}
    public record BulkRequest(List<String> names, Boolean deleteAttachment) {}
    public record BulkMoveRequest(List<String> names, String groupName) {}
    public record AttachmentPage(List<Attachment> items, int total, int page, int size) {}
    public record AttachmentGroup(String name, String displayName, long totalAttachments) {}
    public record AttachmentPolicy(String name, String displayName) {}
    public record AttachmentResolveRequest(List<String> references) {}
}
