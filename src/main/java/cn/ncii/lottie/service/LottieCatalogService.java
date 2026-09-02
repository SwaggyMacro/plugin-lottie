package cn.ncii.lottie.service;

import cn.ncii.lottie.extension.LottieAnimation;
import cn.ncii.lottie.extension.LottieConfig;
import cn.ncii.lottie.extension.LottieGroup;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Group;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.service.AttachmentService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application service for normalized animation imports and library operations. */
@Service
public class LottieCatalogService {

    private static final Logger log = LoggerFactory.getLogger(LottieCatalogService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int DEFAULT_MAX_FILES = 100;
    private static final long MAX_COMPRESSED = 50L * 1024 * 1024;
    private static final long MAX_EXPANDED = 200L * 1024 * 1024;

    private final LottieCatalogRepository repository;
    private final AttachmentService attachmentService;
    private final ReactiveExtensionClient extensionClient;
    private final ReactiveSettingFetcher settingFetcher;

    /** Supports isolated format-normalization tests without a Halo runtime. */
    public LottieCatalogService() {
        this.repository = null;
        this.attachmentService = null;
        this.extensionClient = null;
        this.settingFetcher = null;
    }

    @Autowired
    public LottieCatalogService(LottieCatalogRepository repository, AttachmentService attachmentService,
                                ReactiveExtensionClient extensionClient,
                                ReactiveSettingFetcher settingFetcher) {
        this.repository = repository;
        this.attachmentService = attachmentService;
        this.extensionClient = extensionClient;
        this.settingFetcher = settingFetcher;
    }

    public Flux<LottieAnimation> listAnimations(String group) {
        return repository().listAnimations()
            .filter(item -> {
                LottieAnimation.Spec spec = animationSpec(item);
                return group == null || group.isBlank() || group.equals(spec.getGroupName());
            })
            .sort(LottieCatalogService::compareAnimations);
    }

    private static int compareAnimations(LottieAnimation left, LottieAnimation right) {
        int leftSort = animationSpec(left).getSort() == null ? 0 : animationSpec(left).getSort();
        int rightSort = animationSpec(right).getSort() == null ? 0 : animationSpec(right).getSort();
        int bySort = Integer.compare(leftSort, rightSort);
        if (bySort != 0) return bySort;
        String leftName = left == null || left.getMetadata() == null ? "" : left.getMetadata().getName();
        String rightName = right == null || right.getMetadata() == null ? "" : right.getMetadata().getName();
        return Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER).compare(leftName, rightName);
    }

    public Flux<LottieGroup> listGroups() {
        return repository().listGroups();
    }

    /** Returns the effective import/insertion defaults from plugin settings. */
    public Mono<EffectiveSettings> effectiveSettings() {
        // Prefer intrinsic animation dimensions by default; configured values remain fallback.
        EffectiveSettings fallback = new EffectiveSettings(true, 160, 160, DEFAULT_MAX_FILES, 12);
        if (settingFetcher == null) return Mono.just(fallback);
        return settingFetcher.fetch("general", PluginSettings.class)
            .map(settings -> settings == null ? fallback : settings.toEffective())
            .defaultIfEmpty(fallback)
            .onErrorReturn(fallback);
    }

    public Mono<LottieAnimation> getAnimation(String name) {
        return repository().findAnimation(name);
    }

    /**
     * Removes an animation definition and, optionally, its backing Halo
     * attachment.  Attachment deletion is opt-in because attachments can be
     * managed independently in Halo and may be referenced outside this
     * catalog.
     */
    public Mono<Void> deleteAnimation(String name) {
        return deleteAnimation(name, false);
    }

    public Mono<Void> deleteAnimation(String name, boolean deleteAttachment) {
        return repository().findAnimation(name)
            .flatMap(animation -> {
                String attachmentName = animationSpec(animation).getAttachmentName();
                Mono<Void> removeFile = deleteAttachment
                    ? deleteAttachment(name, attachmentName)
                    : Mono.empty();
                // Do not swallow attachment failures.  If the file cannot be
                // removed, the metadata remains so the user can retry safely.
                return removeFile.then(repository().deleteAnimation(name));
            });
    }

    private Mono<Void> deleteAttachment(String animationName, String attachmentName) {
        if (attachmentService == null || extensionClient == null || attachmentName == null
            || attachmentName.isBlank()) {
            return Mono.empty();
        }
        return repository().listAnimations()
            .filter(other -> !animationName.equals(other.getMetadata() == null
                ? null : other.getMetadata().getName()))
            .any(other -> other.getSpec() != null
                && attachmentName.equals(other.getSpec().getAttachmentName()))
            .flatMap(shared -> {
                if (shared) {
                    return Mono.empty();
                }
                return extensionClient.fetch(Attachment.class, attachmentName)
                    // A stale catalog reference must not prevent deleting the
                    // animation metadata when the attachment was removed from
                    // Halo independently.
                    .onErrorResume(error -> isNotFound(error)
                        ? Mono.empty() : Mono.error(error))
                    .flatMap(this::removeAttachment);
            });
    }

    /**
     * Removes both the backing object and its Attachment extension. Halo's
     * AttachmentService implementations differ: some delete only storage,
     * while others also remove the extension. The second delete is therefore
     * intentional and treats an already-removed resource as success.
     */
    private Mono<Void> removeAttachment(Attachment attachment) {
        if (attachment == null || attachmentService == null || extensionClient == null
            || attachment.getMetadata() == null
            || blankToNull(attachment.getMetadata().getName()) == null) {
            return Mono.empty();
        }
        String attachmentName = attachment.getMetadata().getName();
        Mono<Void> removeStorage = attachmentService.delete(attachment)
            .then()
            // A missing backing object is already in the desired state. For
            // any other storage failure keep the extension so a later retry
            // can still resolve the attachment and repair the catalog.
            .onErrorResume(error -> isNotFound(error)
                ? Mono.empty() : Mono.error(error));
        Mono<Void> removeExtension = Mono.defer(() -> extensionClient.delete(attachment)
            .then()
            .doOnSuccess(ignored -> log.debug("Removed Lottie attachment extension '{}'", attachmentName))
            .onErrorResume(error -> isNotFound(error)
                ? Mono.empty() : Mono.error(error)));

        // Delete storage first, then the extension metadata. This ordering is
        // important: deleting the extension after a storage failure would
        // hide an orphaned object and make a retry impossible.
        return removeStorage
            .then(removeExtension)
            // Some storage providers update the extension asynchronously. A
            // second fetch/delete closes that small race and prevents the
            // Console attachment list from retaining a stale 404 entry.
            .then(Mono.defer(() -> extensionClient.fetch(Attachment.class, attachmentName)
                .flatMap(current -> extensionClient.delete(current).then())
                .onErrorResume(error -> isNotFound(error)
                    ? Mono.empty() : Mono.error(error))));
    }

    private static boolean isNotFound(Throwable error) {
        String message = error == null || error.getMessage() == null
            ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("404") || message.contains("not found")
            || message.contains("does not exist");
    }

    public Mono<Void> bulkDelete(List<String> names) {
        return bulkDelete(names, false);
    }

    public Mono<Void> bulkDelete(List<String> names, boolean deleteAttachment) {
        return Flux.fromIterable(names == null ? List.of() : names)
            .concatMap(name -> deleteAnimation(name, deleteAttachment))
            .then();
    }

    public Mono<Void> bulkMove(List<String> names, String groupName) {
        String target = groupName == null || groupName.isBlank() ? null : resourceName(groupName, "group");
        Mono<Void> ensure = target == null ? Mono.empty() : ensureGroup(groupName);
        return ensure.thenMany(Flux.fromIterable(names == null ? List.of() : names)
            .concatMap(name -> repository().findAnimation(name).flatMap(animation -> {
                animationSpec(animation).setGroupName(target);
                return repository().saveAnimation(animation);
            }))).then();
    }

    /** Persists the complete order of one group after a drag-and-drop action. */
    public Mono<Void> reorderAnimations(String groupName, List<String> names) {
        String target = groupName == null || groupName.isBlank() ? null : resourceName(groupName, "group");
        List<String> requested = names == null ? List.of() : names.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(name -> !name.isBlank())
            .distinct()
            .toList();
        return repository().listAnimations()
            .filter(animation -> Objects.equals(target, animationSpec(animation).getGroupName()))
            .collectList()
            .flatMapMany(items -> {
                Map<String, Integer> requestedOrder = new HashMap<>();
                for (int index = 0; index < requested.size(); index++) {
                    requestedOrder.put(resourceName(requested.get(index), "animation"), index);
                }
                items.sort(Comparator
                    .comparingInt((LottieAnimation animation) -> requestedOrder.getOrDefault(
                        animation.getMetadata() == null ? null : animation.getMetadata().getName(), Integer.MAX_VALUE))
                    .thenComparingInt(animation -> animationSpec(animation).getSort() == null
                        ? 0 : animationSpec(animation).getSort())
                    .thenComparing(animation -> animation.getMetadata() == null
                        ? "" : Objects.requireNonNullElse(animation.getMetadata().getName(), ""),
                        String.CASE_INSENSITIVE_ORDER));
                for (int index = 0; index < items.size(); index++) {
                    animationSpec(items.get(index)).setSort(index);
                }
                return Flux.fromIterable(items).concatMap(repository()::saveAnimation);
            })
            .then();
    }

    /** Persists the complete order of the top-level group list. */
    public Mono<Void> reorderGroups(List<String> names) {
        List<String> requested = names == null ? List.of() : names.stream()
            .filter(Objects::nonNull).map(String::trim).filter(name -> !name.isBlank()).distinct().toList();
        Map<String, Integer> requestedOrder = new HashMap<>();
        for (int index = 0; index < requested.size(); index++) {
            requestedOrder.put(resourceName(requested.get(index), "group"), index);
        }
        return repository().listGroups().collectList().flatMapMany(items -> {
            items.sort(Comparator
                .comparingInt((LottieGroup group) -> requestedOrder.getOrDefault(
                    group.getMetadata() == null ? null : group.getMetadata().getName(), Integer.MAX_VALUE))
                .thenComparingInt(group -> groupSpec(group).getSort() == null ? 0 : groupSpec(group).getSort())
                .thenComparing(group -> group.getMetadata() == null ? "" : Objects.requireNonNullElse(group.getMetadata().getName(), ""), String.CASE_INSENSITIVE_ORDER));
            for (int index = 0; index < items.size(); index++) groupSpec(items.get(index)).setSort(index);
            return Flux.fromIterable(items).concatMap(repository()::saveGroup);
        }).then();
    }

    public Mono<Void> deleteGroup(String name) {
        String groupName = resourceName(name, "group");
        return repository().findGroup(groupName)
            .flatMap(group -> repository().listGroups()
            .filter(child -> groupName.equals(groupSpec(child).getParentName()))
            .concatMap(child -> {
                    groupSpec(child).setParentName(blankToNull(groupSpec(group).getParentName()));
                    return repository().saveGroup(child);
                })
                .then())
            .then(repository().listAnimations()
            .filter(animation -> groupName.equals(animationSpec(animation).getGroupName()))
            .concatMap(animation -> {
                animationSpec(animation).setGroupName(null);
                return repository().saveAnimation(animation);
            })
            .then(repository().deleteGroup(groupName)));
    }

    public Mono<LottieGroup> saveGroup(String name, String displayName, String parentName,
                                        String description) {
        return saveGroup(name, displayName, parentName, description, null);
    }

    public Mono<LottieGroup> saveGroup(String name, String displayName, String parentName,
                                        String description, Integer sort) {
        String resourceName = resourceName(name, "group");
        String normalizedParent = blankToNull(parentName);
        if (normalizedParent != null
            && resourceName.equals(resourceName(normalizedParent, "group"))) {
            throw new IllegalArgumentException("A group cannot be its own parent");
        }
        return repository().findGroup(resourceName)
            .defaultIfEmpty(new LottieGroup())
            .map(group -> {
                ensureMetadata(group);
                groupSpec(group);
                group.getMetadata().setName(resourceName);
                group.getSpec().setDisplayName(displayName == null || displayName.isBlank()
                    ? resourceName : displayName.trim());
                group.getSpec().setParentName(normalizedParent == null
                    ? null : resourceName(normalizedParent, "group"));
                group.getSpec().setDescription(blankToNull(description));
                if (sort != null) {
                    group.getSpec().setSort(Math.max(0, sort));
                }
                return group;
            })
            .flatMap(repository()::saveGroup);
    }

    public Mono<LottieAnimation> saveAnimation(String name, String displayName, String groupName,
                                                String format, String mediaType,
                                                String attachmentName, String attachmentUrl,
                                                String sha256, LottieConfig defaults,
                                                String sourceFileName, Boolean enabled, List<String> tags) {
        return saveAnimation(name, displayName, groupName, format, mediaType, attachmentName,
            attachmentUrl, sha256, defaults, sourceFileName, enabled, tags, null);
    }

    public Mono<LottieAnimation> saveAnimation(String name, String displayName, String groupName,
                                                String format, String mediaType,
                                                String attachmentName, String attachmentUrl,
                                                String sha256, LottieConfig defaults,
                                                String sourceFileName, Boolean enabled, List<String> tags,
                                                Integer sort) {
        String resourceName = resourceName(name, "animation");
        String normalizedGroupName = groupName == null || groupName.isBlank()
            ? null : resourceName(groupName, "group");
        return repository().findAnimation(resourceName)
            .defaultIfEmpty(new LottieAnimation())
            .map(animation -> {
                ensureMetadata(animation);
                animationSpec(animation);
                animation.getMetadata().setName(resourceName);
                animation.getSpec().setDisplayName(displayName == null || displayName.isBlank()
                    ? resourceName : displayName.trim());
                animation.getSpec().setGroupName(normalizedGroupName);
                if (sort != null) {
                    animation.getSpec().setSort(Math.max(0, sort));
                }
                animation.getSpec().setFormat(format == null || format.isBlank() ? "json" : format);
                animation.getSpec().setMediaType(mediaType == null || mediaType.isBlank()
                    ? "application/json" : mediaType);
                if (attachmentName != null && !attachmentName.isBlank()) {
                    animation.getSpec().setAttachmentName(attachmentName.trim());
                }
                if (attachmentUrl != null && !attachmentUrl.isBlank()) {
                    animation.getSpec().setAttachmentUrl(attachmentUrl.trim());
                }
                animation.getSpec().setDefaults(defaults == null ? new LottieConfig() : defaults);
                animation.getSpec().setSourceFileName(blankToNull(sourceFileName));
                animation.getSpec().setTags(tags == null ? new ArrayList<>() : tags.stream().map(LottieCatalogService::blankToNull).filter(Objects::nonNull).distinct().toList());
                if (enabled != null) {
                    animation.getSpec().setEnabled(enabled);
                }
                if (sha256 != null && !sha256.isBlank()) {
                    animation.getSpec().setSha256(sha256.trim());
                }
                return animation;
            })
            .flatMap(repository()::saveAnimation);
    }

    /** Previews an archive using the default limit for standalone callers. */
    public List<ImportCandidate> previewZip(byte[] archive) throws IOException {
        return previewZip(archive, DEFAULT_MAX_FILES);
    }

    /** Previews an archive using the configured plugin limits. */
    public Mono<List<ImportCandidate>> previewZipWithSettings(byte[] archive) {
        return effectiveSettings().flatMap(settings -> {
            try {
                return Mono.just(previewZip(archive, settings.maxFiles()));
            } catch (IOException | RuntimeException exception) {
                return Mono.error(exception);
            }
        });
    }

    private List<ImportCandidate> previewZip(byte[] archive, int maxFiles) throws IOException {
        if (archive.length > MAX_COMPRESSED) {
            throw new IllegalArgumentException("Compressed archive exceeds 50MB");
        }
        int fileLimit = maxFiles > 0 ? maxFiles : DEFAULT_MAX_FILES;
        List<ImportCandidate> candidates = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            long expanded = 0;
            int entries = 0;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (++entries > fileLimit) {
                    throw new IllegalArgumentException("Archive contains more than " + fileLimit + " files");
                }
                String format = formatOf(entry.getName());
                if (format == null) continue;
                byte[] bytes = readLimited(input, MAX_EXPANDED - expanded);
                Normalized normalized = normalize(bytes, format);
                long normalizedSize = normalized.content().getBytes(StandardCharsets.UTF_8).length;
                if (normalizedSize > MAX_EXPANDED - expanded) {
                    throw new IllegalArgumentException("Expanded import content exceeds 200MB");
                }
                expanded += normalizedSize;
                candidates.add(new ImportCandidate(entry.getName(), inferDisplayName(entry.getName()),
                    inferGroup(entry.getName()), normalized.format(), normalized.mediaType(), normalized.content(),
                    sha256(normalized.content().getBytes(StandardCharsets.UTF_8)), normalized.width(),
                    normalized.height()));
            }
        }
        return candidates;
    }

    public Mono<List<LottieAnimation>> importZip(byte[] archive, String duplicateMode) throws IOException {
        return importZip(archive, duplicateMode, null);
    }

    public Mono<List<LottieAnimation>> importZip(byte[] archive, String duplicateMode,
                                                  String attachmentGroupName) throws IOException {
        return importZip(archive, duplicateMode, null, attachmentGroupName);
    }

    public Mono<List<LottieAnimation>> importZip(byte[] archive, String duplicateMode,
                                                  String groupName, String attachmentGroupName) throws IOException {
        return importZip(archive, duplicateMode, groupName, attachmentGroupName, null);
    }

    public Mono<List<LottieAnimation>> importZip(byte[] archive, String duplicateMode,
                                                  String groupName, String attachmentGroupName,
                                                  String attachmentPolicyName) throws IOException {
        return previewZipWithSettings(archive)
            .flatMapMany(Flux::fromIterable)
            .map(candidate -> groupName == null || groupName.isBlank()
                ? candidate : candidate.withGroupName(groupName))
            .concatMap(candidate -> importCandidate(candidate, duplicateMode, attachmentGroupName, attachmentPolicyName))
            .collectList();
    }

    public ImportCandidate normalizeUpload(String filename, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Animation file is empty");
        }
        if (bytes.length > MAX_EXPANDED) {
            throw new IllegalArgumentException("Animation file exceeds 200MB");
        }
        String format = formatOf(filename);
        if (format == null) {
            throw new IllegalArgumentException("Only JSON, .lottie, and TGS files are supported");
        }
        Normalized normalized = normalize(bytes, format);
        return new ImportCandidate(filename, inferDisplayName(filename), null, normalized.format(),
            normalized.mediaType(), normalized.content(),
            sha256(normalized.content().getBytes(StandardCharsets.UTF_8)), normalized.width(),
            normalized.height());

    }

    public Mono<LottieAnimation> importCandidate(ImportCandidate candidate, String duplicateMode) {
        return importCandidate(candidate, duplicateMode, null);
    }

    public Mono<LottieAnimation> importCandidate(ImportCandidate candidate, String duplicateMode,
                                                  String attachmentGroupName) {
        return importCandidate(candidate, duplicateMode, attachmentGroupName, null);
    }

    public Mono<LottieAnimation> importCandidate(ImportCandidate candidate, String duplicateMode,
                                                  String attachmentGroupName,
                                                  String attachmentPolicyName) {
        if (candidate == null) {
            return Mono.error(new IllegalArgumentException("Animation import candidate is required"));
        }
        String mode = duplicateMode == null || duplicateMode.isBlank()
            ? "skip" : duplicateMode.trim().toLowerCase(Locale.ROOT);
        if (!List.of("skip", "overwrite", "rename", "duplicate").contains(mode)) {
            return Mono.error(new IllegalArgumentException(
                "duplicateMode must be one of skip, overwrite, rename, or duplicate"));
        }
        return findBySha256(candidate.sha256()).hasElement().flatMap(duplicate -> {
            if (duplicate && "skip".equals(mode)) {
                return Mono.empty();
            }
            if (duplicate && "overwrite".equals(mode)) {
                // The payload is already identical by hash. Update catalog
                // metadata in place and keep the existing attachment reference.
                return findBySha256(candidate.sha256()).flatMap(existing ->
                    Mono.defer(() -> {
                        ensureMetadata(existing);
                        String existingName = blankToNull(existing.getMetadata().getName());
                        if (existingName == null) {
                            return Mono.error(new IllegalStateException(
                                "Cannot overwrite animation without a resource name"));
                        }
                        LottieAnimation.Spec existingSpec = animationSpec(existing);
                        return defaultsFor(candidate).flatMap(defaults -> ensureGroup(candidate.groupName())
                            .then(saveAnimation(existingName, candidate.displayName(), candidate.groupName(),
                                candidate.format(), candidate.mediaType(), existingSpec.getAttachmentName(),
                                existingSpec.getAttachmentUrl(), candidate.sha256(), defaults,
                                candidate.sourceFileName(), true, existingSpec.getTags())));
                    }));
            }
            return uniqueAnimationName(candidate.displayName(), duplicate && "duplicate".equals(mode))
                .flatMap(name -> defaultsFor(candidate).flatMap(defaults -> ensureGroup(candidate.groupName())
                    .then(saveImportedAnimation(name, candidate, attachmentGroupName, attachmentPolicyName, defaults))));
        });
    }

    private Mono<LottieAnimation> saveImportedAnimation(String name, ImportCandidate candidate,
                                                         String attachmentGroupName,
                                                         String attachmentPolicyName,
                                                         LottieConfig defaults) {
        if (attachmentService == null) {
            return saveAnimation(name, candidate.displayName(), candidate.groupName(), candidate.format(),
                candidate.mediaType(), null, null, candidate.sha256(), defaults, candidate.sourceFileName(), true, List.of());
        }
        StoredPayload stored = toStoredPayload(candidate, name);
        byte[] payload = stored.bytes();
        // ZIP entries may contain directory separators (for example
        // "stickers/smile.json").  Attachment policies expect a plain file
        // name; keep the path in sourceFileName for grouping, but upload only
        // a sanitized basename.
        String filename = attachmentFilename(candidate.sourceFileName(), name, stored.format());
        MediaType mediaType = MediaType.parseMediaType(stored.mediaType());
        String targetAttachmentGroup = normalizeAttachmentGroupName(attachmentGroupName);
        // AttachmentService may subscribe more than once (for example when
        // the selected group cannot be resolved and the policy-default path
        // is used). Create a fresh DataBuffer for every subscription so a
        // consumed/released buffer is never reused on a retry.
        Flux<org.springframework.core.io.buffer.DataBuffer> buffers = Flux.defer(() ->
            Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(payload)));
        
        return attachmentPolicyName(attachmentPolicyName)
            .doOnNext(policyName -> log.debug("Resolved Lottie upload policy '{}' for '{}' (requested attachment group '{}')",
                policyName, filename, targetAttachmentGroup))
            .flatMap(policyName -> resolveAttachmentGroup(targetAttachmentGroup)
                .doOnNext(groupName -> log.debug("Resolved Lottie upload attachment group '{}' for '{}'",
                    groupName, filename))
                .flatMap(groupName -> uploadAttachment(policyName, groupName, filename,
                    buffers, mediaType))
                // `Mono` cannot carry null, while Halo accepts a null group to
                // use the policy default.  When no group was selected the
                // resolver intentionally completes empty, so invoke upload
                // explicitly with a null group instead of short-circuiting.
                .switchIfEmpty(Mono.defer(() -> uploadAttachment(policyName, null, filename,
                    buffers, mediaType))))
                .flatMap(attachment -> attachmentService.getPermalink(attachment)
                    .map(java.net.URI::toString)
                    .defaultIfEmpty("")
                    .flatMap(url -> saveAnimationReference(name, candidate, stored, attachment, url, defaults))
                // Any failure after upload (permalink resolution or catalog
                // persistence) must remove the newly-created attachment so a
                // retry cannot accumulate unreachable files.
                .onErrorResume(error -> removeAttachment(attachment)
                    .then(Mono.error(error))));
    }

    private Mono<Attachment> uploadAttachment(String policyName, String groupName,
                                               String filename,
                                               Flux<org.springframework.core.io.buffer.DataBuffer> content,
                                               MediaType mediaType) {
        /*
         * Use Halo's unambiguous content-stream overload:
         * upload(policyName, groupName, filename, content, mediaType).
         * The FilePart overload has a version-dependent first argument and
         * older implementations could interpret a selected attachment group
         * as a Policy name (for example Policy/attachment-group-...).
         */
        if (policyName == null || policyName.isBlank()) {
            return Mono.error(new IllegalStateException(
                "Cannot upload Lottie animation: Halo attachment policy is empty"));
        }
        String normalizedGroup = normalizeAttachmentGroupName(groupName);
        if (normalizedGroup != null
            && (policyName.equalsIgnoreCase(normalizedGroup)
                || Objects.equals(attachmentGroupAlias(policyName), attachmentGroupAlias(normalizedGroup)))) {
            return Mono.error(new IllegalStateException(
                "Resolved Halo attachment policy is the same as the selected attachment group: "
                    + policyName));
        }
        log.debug("Uploading Lottie attachment '{}' with policy '{}' and group '{}'",
            filename, policyName, normalizedGroup);

        /*
         * Halo 2.26 declares (policyName, groupName), but a few development
         * runtimes have shipped an implementation compiled with these two
         * values reversed. Retry only when the storage layer explicitly says
         * it tried to resolve a group id (or null) as a Policy. The content
         * Flux is cold, so the retry receives a fresh DataBuffer.
         */
        Mono<Attachment> primary = attachmentService.upload(
            policyName, normalizedGroup, filename, content, mediaType);
        return primary.onErrorResume(error -> {
            if (!isPolicyArgumentMismatch(error)) {
                return Mono.error(error);
            }
            log.warn("Halo rejected Lottie upload arguments as policy/group in the declared order; "
                + "retrying compatibility order (policy='{}', group='{}')", policyName, normalizedGroup);
            return attachmentService.upload(
                    normalizedGroup, policyName, filename, content, mediaType)
                .onErrorResume(fallback -> Mono.error(new IllegalStateException(
                    "Halo rejected both attachment upload argument orders "
                        + "(policy='" + policyName + "', group='" + normalizedGroup + "')",
                    fallback)));
        }).switchIfEmpty(Mono.error(new IllegalStateException(
            "Halo attachment upload returned no attachment for file '" + filename + "'")));
    }

    private static boolean isPolicyArgumentMismatch(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage() == null
                ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("policy/null")
                || message.contains("policy/undefined")
                || message.contains("policy/attachment-group-")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Resolve and validate the selected Halo attachment group, if any. */
    private Mono<String> resolveAttachmentGroup(String groupName) {
        String requested = normalizeAttachmentGroupName(groupName);
        if (requested == null) {
            // Mono cannot contain null. An empty result tells the caller to
            // invoke AttachmentService with a null group (policy default).
            return Mono.empty();
        }
        if (extensionClient == null) {
            return Mono.error(new IllegalStateException("Halo extension client is unavailable"));
        }
        // The Console normally sends metadata.name. Older Console builds and
        // custom clients may send a display name or a shortened
        // `attachment-group-...` alias, so resolve those against the actual
        // Group extensions and always return metadata.name to Halo.
        Mono<String> direct = extensionClient.fetch(Group.class, requested)
            .map(LottieCatalogService::groupMetadataName)
            .filter(Objects::nonNull)
            .onErrorResume(error -> {
                log.debug("Attachment group '{}' was not found by metadata name", requested, error);
                return Mono.empty();
            });
        Mono<String> alias = extensionClient
            .listAll(Group.class, run.halo.app.extension.ListOptions.builder().build(),
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Order.asc("metadata.name")))
            .filter(group -> attachmentGroupMatches(group, requested))
            .map(LottieCatalogService::groupMetadataName)
            .filter(Objects::nonNull)
            .next()
            .onErrorResume(error -> {
                log.warn("Unable to enumerate Halo attachment groups while resolving '{}'", requested, error);
                return Mono.empty();
            });
        return direct.switchIfEmpty(alias)
            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                "Halo attachment group was not found: " + requested)));
    }

    private static String groupMetadataName(Group group) {
        if (group == null || group.getMetadata() == null) return null;
        return blankToNull(group.getMetadata().getName());
    }

    private static boolean attachmentGroupMatches(Group group, String requested) {
        String metadataName = groupMetadataName(group);
        if (metadataName == null) return false;
        String displayName = group.getSpec() == null ? null : group.getSpec().getDisplayName();
        if (metadataName.equalsIgnoreCase(requested)
            || (displayName != null && displayName.trim().equalsIgnoreCase(requested))) {
            return true;
        }
        String requestedAlias = attachmentGroupAlias(requested);
        return requestedAlias != null
            && (requestedAlias.equals(attachmentGroupAlias(metadataName))
                || requestedAlias.equals(attachmentGroupAlias(displayName)));
    }

    /** Canonical comparison key for metadata names and human-readable aliases. */
    private static String attachmentGroupAlias(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;
        normalized = normalized.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        if (normalized.startsWith("attachment-group-")) {
            normalized = normalized.substring("attachment-group-".length());
        }
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * Resolves a real Halo storage policy.
     *
     * <p>The plugin used to read an {@code attachmentPolicy} setting. That
     * setting was ambiguous with an attachment group and could leave stale
     * values such as {@code attachment-group-...} or {@code null} in the
     * upload call. The attachment group is a separate argument and must never
     * participate in policy resolution, so policies are now discovered only
     * from Halo's Policy extensions and each candidate is fetched again before
     * use.
     */
    private Mono<String> attachmentPolicyName() {
        return attachmentPolicyName(null);
    }

    private Mono<String> attachmentPolicyName(String requestedPolicy) {
        String requested = validPolicyName(requestedPolicy);
        if (requestedPolicy != null && !requestedPolicy.isBlank() && requested == null) {
            return Mono.error(new IllegalArgumentException("Invalid Halo attachment policy"));
        }
        if (requested != null) {
            if (extensionClient == null) return Mono.error(new IllegalStateException("Halo extension client is unavailable"));
            return extensionClient.fetch(Policy.class, requested)
                .map(LottieCatalogService::policyMetadataName)
                .filter(Objects::nonNull)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Halo attachment policy was not found: " + requested)));
        }
        if (extensionClient == null) {
            return Mono.error(new IllegalStateException("Halo extension client is unavailable"));
        }
        /*
         * Do not derive a policy from an attachment or from the selected
         * attachment group. Older installations can contain malformed
         * attachments whose policyName is actually a group id. The Policy
         * extension is the source of truth for AttachmentService.
         */
        Mono<Set<String>> groupKeys = extensionClient
            .listAll(Group.class, run.halo.app.extension.ListOptions.builder().build(),
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Order.asc("metadata.name")))
            .flatMap(group -> Flux.concat(
                    Mono.justOrEmpty(groupMetadataName(group)),
                    Mono.justOrEmpty(group == null || group.getSpec() == null
                        ? null : group.getSpec().getDisplayName()),
                    Mono.justOrEmpty(attachmentGroupAlias(groupMetadataName(group))),
                    Mono.justOrEmpty(attachmentGroupAlias(group == null || group.getSpec() == null
                        ? null : group.getSpec().getDisplayName())))
                .map(LottieCatalogService::groupKey))
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet())
            // Group enumeration is only a safety check. A storage provider
            // may not expose groups at all, in which case policies still can
            // be resolved and uploads can use the policy's default group.
            .onErrorReturn(Set.of());

        Mono<List<String>> policyNames = extensionClient
            .listAll(Policy.class, run.halo.app.extension.ListOptions.builder().build(),
                org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Order.asc("metadata.name")))
            .map(LottieCatalogService::policyMetadataName)
            .filter(Objects::nonNull)
            // A stale extension list can outlive a deleted Policy in the
            // development database. Verify each candidate before exposing it
            // to AttachmentService.
            .concatMap(name -> extensionClient.fetch(Policy.class, name)
                .map(LottieCatalogService::policyMetadataName)
                .filter(Objects::nonNull)
                .onErrorResume(error -> Mono.empty()))
            .distinct()
            .collectList()
            .onErrorReturn(List.of());

        Mono<String> preferredPolicy = groupKeys.flatMap(knownGroups ->
            extensionClient.fetch(Policy.class, "default-policy")
                .map(LottieCatalogService::policyMetadataName)
                .filter(Objects::nonNull)
                .filter(name -> !knownGroups.contains(groupKey(name)))
                .onErrorResume(error -> Mono.empty()));

        Mono<String> enumeratedPolicy = Mono.zip(groupKeys, policyNames)
            .flatMapMany(tuple -> {
                Set<String> knownGroups = tuple.getT1();
                List<String> names = tuple.getT2();
                return Flux.fromIterable(names)
                    .filter(name -> !knownGroups.contains(groupKey(name)))
                    .sort((left, right) -> {
                        boolean leftDefault = "default-policy".equalsIgnoreCase(left);
                        boolean rightDefault = "default-policy".equalsIgnoreCase(right);
                        return Boolean.compare(rightDefault, leftDefault);
                    });
            })
            .next();

        return preferredPolicy.switchIfEmpty(enumeratedPolicy)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "No Halo attachment policy is available. Configure a storage policy before importing.")));
    }

    private Mono<LottieConfig> defaultsFor(ImportCandidate candidate) {
        return effectiveSettings().map(settings -> settings.readAnimationDimensions()
            ? defaultConfig(candidate.width(), candidate.height())
            : defaultConfig(settings.defaultWidth(), settings.defaultHeight()));
    }

    private static String policyMetadataName(Policy policy) {
        return policy == null || policy.getMetadata() == null
            ? null : validPolicyName(policy.getMetadata().getName());
    }

    private static String groupKey(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) return null;
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeAttachmentGroupName(String groupName) {
        String normalized = blankToNull(groupName);
        if (normalized != null && ("null".equalsIgnoreCase(normalized)
            || "undefined".equalsIgnoreCase(normalized))) {
            normalized = null;
        }
        if (normalized == null) return null;
        // Never let a policy-shaped value leak into the policy argument. The
        // group can legitimately be named attachment-group-..., so this is
        // deliberately only a null/blank normalization helper.
        return normalized;
    }

    private static String validPolicyName(String value) {
        String normalized = blankToNull(value);
        if (normalized == null
            || "null".equalsIgnoreCase(normalized)
            || "undefined".equalsIgnoreCase(normalized)) {
            return null;
        }
        // Halo-generated attachment groups use this reserved prefix. A
        // policy with the same prefix is technically possible, but accepting
        // it makes a malformed/stale group value indistinguishable from a
        // real policy and produces Policy/attachment-group-* failures. Keep
        // the upload path deterministic and require a storage policy name.
        if (normalized.toLowerCase(Locale.ROOT).startsWith("attachment-group-")) {
            return null;
        }
        return normalized;
    }

    private Mono<LottieAnimation> saveAnimationReference(String name, ImportCandidate candidate,
                                                          StoredPayload stored,
                                                          Attachment attachment, String url,
                                                          LottieConfig defaults) {
        String attachmentName = attachment == null || attachment.getMetadata() == null
            ? null : attachment.getMetadata().getName();
        if (attachmentName == null || attachmentName.isBlank()) {
            return Mono.error(new IllegalStateException("Halo upload returned an attachment without a name"));
        }
        return saveAnimation(name, candidate.displayName(), candidate.groupName(), stored.format(),
            stored.mediaType(), attachmentName, url, candidate.sha256(),
            defaults, candidate.sourceFileName(), true, List.of());
    }

    /** Converts JSON/TGS payloads to a minimal dotLottie ZIP before attachment upload. */
    private static StoredPayload toStoredPayload(ImportCandidate candidate, String animationName) {
        try {
            if ("lottie".equalsIgnoreCase(candidate.format())) {
                return new StoredPayload("lottie", "application/zip", decodeDataUri(candidate.content()));
            }
            byte[] json = candidate.content().getBytes(StandardCharsets.UTF_8);
            String safeId = resourceName(animationName, "animation");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(output,
                StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                String manifest = "{\"version\":\"1.0\",\"animations\":[{\"id\":\""
                    + safeId + "\",\"filename\":\"animations/" + safeId + ".json\"}]}";
                zip.write(manifest.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("animations/" + safeId + ".json"));
                zip.write(json);
                zip.closeEntry();
            }
            return new StoredPayload("lottie", "application/zip", output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to package animation as dotLottie", exception);
        }
    }

    /**
     * Returns a storage-safe basename while preserving the original source path
     * in the catalog metadata.  This prevents ZIP directory traversal and
     * platform-specific filename failures in attachment handlers.
     */
    private static String attachmentFilename(String sourceFileName, String fallbackName, String format) {
        String source = Objects.requireNonNullElse(sourceFileName, "").replace('\\', '/');
        int slash = source.lastIndexOf('/');
        String basename = slash >= 0 ? source.substring(slash + 1) : source;
        basename = basename.replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").trim();
        if (basename.isBlank() || ".".equals(basename) || "..".equals(basename)) {
            basename = fallbackName + "." + format;
        }
        if ("json".equals(format) && basename.toLowerCase(Locale.ROOT).endsWith(".tgs")) {
            basename = basename.substring(0, basename.length() - 4) + ".json";
        }
        if ("lottie".equals(format)) {
            String lower = basename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".json") || lower.endsWith(".tgs") || lower.endsWith(".lottie")) {
                basename = basename.substring(0, basename.lastIndexOf('.')) + ".lottie";
            } else {
                basename = basename + ".lottie";
            }
        }
        return truncateFilename(basename, 255);
    }

    private static String truncateFilename(String filename, int maximumLength) {
        if (filename.length() <= maximumLength) return filename;
        int dot = filename.lastIndexOf('.');
        String extension = dot > 0 ? filename.substring(dot) : "";
        int stemLength = Math.max(1, maximumLength - extension.length());
        return filename.substring(0, stemLength) + extension;
    }

    private static byte[] decodeDataUri(String value) {
        if (value == null) return new byte[0];
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0) {
            return Base64.getDecoder().decode(value.substring(comma + 1));
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private Mono<LottieAnimation> findBySha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) return Mono.empty();
        return repository().listAnimations()
            .filter(item -> sha256.equals(animationSpec(item).getSha256()))
            .next();
    }

    private Mono<Void> ensureGroup(String groupName) {
        if (groupName == null || groupName.isBlank()) return Mono.empty();
        String resourceName = resourceName(groupName, "group");
        return repository().findGroup(resourceName)
            .switchIfEmpty(saveGroup(resourceName, groupName, null, null))
            .then();
    }

    private Mono<String> uniqueAnimationName(String displayName, boolean appendRandomSuffix) {
        String base = resourceName(displayName, "animation");
        if (appendRandomSuffix) {
            return Mono.just(truncateResourceName(base, 244) + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        return nextAvailableName(base, 1);
    }

    private Mono<String> nextAvailableName(String base, int ordinal) {
        String suffix = ordinal == 1 ? "" : "-" + ordinal;
        String candidate = truncateResourceName(base, 253 - suffix.length()) + suffix;
        return repository().findAnimation(candidate)
            .flatMap(ignored -> nextAvailableName(base, ordinal + 1))
            .switchIfEmpty(Mono.just(candidate));
    }

    private static Normalized normalize(byte[] bytes, String format) throws IOException {
        if ("tgs".equals(format)) {
            try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = readLimited(input, MAX_EXPANDED);
                format = "json";
            }
        }
        if ("json".equals(format)) {
            String json = new String(bytes, StandardCharsets.UTF_8).trim();
            JsonNode document;
            try {
                document = JSON.readTree(json);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Invalid Lottie JSON", exception);
            }
            if (document == null || !document.isObject()) {
                throw new IllegalArgumentException("Invalid Lottie JSON");
            }
            Dimensions dimensions = dimensionsOf(document);
            return new Normalized("json", "application/json", json, dimensions.width(), dimensions.height());
        }
        Dimensions dimensions = validateDotLottie(bytes);
        return new Normalized("lottie", "application/octet-stream",
            "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes),
            dimensions.width(), dimensions.height());
    }

    private static Dimensions dimensionsOf(JsonNode document) {
        int width = document != null && document.path("w").canConvertToInt() ? document.path("w").asInt() : 160;
        int height = document != null && document.path("h").canConvertToInt() ? document.path("h").asInt() : 160;
        return new Dimensions(clampDimension(width), clampDimension(height));
    }

    private static int clampDimension(int value) {
        return value > 0 && value <= 4096 ? value : 160;
    }

    /** Validates a dotLottie package and extracts its first animation size. */
    private static Dimensions validateDotLottie(byte[] bytes) throws IOException {
        boolean manifestFound = false;
        Dimensions dimensions = new Dimensions(160, 160);
        long expanded = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.contains("..")) throw new IllegalArgumentException("dotLottie package contains an unsafe path");
                byte[] content = readLimited(input, MAX_EXPANDED - expanded);
                expanded += content.length;
                if ("manifest.json".equalsIgnoreCase(name)) {
                    try {
                        JsonNode manifest = JSON.readTree(content);
                        if (manifest == null || !manifest.isObject()) throw new IllegalArgumentException("dotLottie manifest.json must be an object");
                    } catch (IllegalArgumentException exception) {
                        throw exception;
                    } catch (Exception exception) {
                        throw new IllegalArgumentException("Invalid dotLottie manifest.json", exception);
                    }
                    manifestFound = true;
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".json") && dimensions.width() == 160 && dimensions.height() == 160) {
                    try {
                        JsonNode animation = JSON.readTree(content);
                        if (animation != null && animation.isObject() && animation.path("w").canConvertToInt() && animation.path("h").canConvertToInt()) {
                            dimensions = dimensionsOf(animation);
                        }
                    } catch (Exception ignored) {
                        // Optional metadata JSON entries do not need to contain animation dimensions.
                    }
                }
            }
        }
        if (!manifestFound) throw new IllegalArgumentException("dotLottie package is missing manifest.json");
        return dimensions;
    }
    private static byte[] readLimited(java.io.InputStream input, long limit) throws IOException {
        if (limit <= 0) throw new IllegalArgumentException("Expanded import content exceeds 200MB");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > limit) {
                throw new IllegalArgumentException("Expanded import content exceeds 200MB");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String formatOf(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".lottie")) return "lottie";
        if (lower.endsWith(".tgs")) return "tgs";
        return null;
    }

    private static String inferDisplayName(String filename) {
        String name = Objects.requireNonNullElse(filename, "animation").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.replaceFirst("(?i)\\.(json|lottie|tgs)$", "");
    }

    private static String inferGroup(String filename) {
        String name = Objects.requireNonNullElse(filename, "").replace('\\', '/');
        int slash = name.lastIndexOf('/');
        return slash > 0 ? name.substring(0, slash) : null;
    }

    private static String resourceName(String value, String fallback) {
        String source = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String normalized = source.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? fallback : truncateResourceName(normalized, 253);
    }

    private static String truncateResourceName(String value, int maximumLength) {
        String result = value.length() <= maximumLength ? value : value.substring(0, maximumLength);
        return result.replaceAll("-+$", "");
    }

    private static LottieConfig defaultConfig(int width, int height) {
        LottieConfig config = new LottieConfig();
        config.setWidth(clampDimension(width));
        config.setHeight(clampDimension(height));
        return config;
    }
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void ensureMetadata(run.halo.app.extension.Extension extension) {
        if (extension.getMetadata() == null) {
            extension.setMetadata(new Metadata());
        }
    }

    private static LottieAnimation.Spec animationSpec(LottieAnimation animation) {
        if (animation == null) {
            return new LottieAnimation.Spec();
        }
        if (animation.getSpec() == null) {
            animation.setSpec(new LottieAnimation.Spec());
        }
        return animation.getSpec();
    }

    private static LottieGroup.Spec groupSpec(LottieGroup group) {
        if (group == null) {
            return new LottieGroup.Spec();
        }
        if (group.getSpec() == null) {
            group.setSpec(new LottieGroup.Spec());
        }
        return group.getSpec();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate animation hash", exception);
        }
    }

    private LottieCatalogRepository repository() {
        if (repository == null) throw new IllegalStateException("A Halo catalog repository is required for library operations");
        return repository;
    }

    public static final class PluginSettings {
        public Integer maxFiles = DEFAULT_MAX_FILES;
        public Boolean readAnimationDimensions = true;
        public Integer defaultWidth = 160;
        public Integer defaultHeight = 160;
        public Integer maxRecentItems = 12;

        EffectiveSettings toEffective() {
            return new EffectiveSettings(Boolean.TRUE.equals(readAnimationDimensions),
                clampDimension(defaultWidth == null ? 160 : defaultWidth),
                clampDimension(defaultHeight == null ? 160 : defaultHeight),
                maxFiles == null || maxFiles < 1 ? DEFAULT_MAX_FILES : maxFiles,
                maxRecentItems == null || maxRecentItems < 1 ? 12 : Math.min(maxRecentItems, 100));
        }
    }

    public record EffectiveSettings(boolean readAnimationDimensions, int defaultWidth, int defaultHeight,
                                    int maxFiles, int maxRecentItems) {}

    public record ImportCandidate(String sourceFileName, String displayName, String groupName,
                                  String format, String mediaType, @JsonIgnore String content,
                                  String sha256, int width, int height) {
        public ImportCandidate(String sourceFileName, String displayName, String groupName,
                               String format, String mediaType, String content, String sha256) {
            this(sourceFileName, displayName, groupName, format, mediaType, content, sha256, 160, 160);
        }

        public ImportCandidate withGroupName(String groupName) {
            return new ImportCandidate(sourceFileName, displayName, groupName, format, mediaType, content, sha256, width, height);
        }
    }

    private record StoredPayload(String format, String mediaType, byte[] bytes) {}

    private record Normalized(String format, String mediaType, String content, int width, int height) {}

    private record Dimensions(int width, int height) {}
}
