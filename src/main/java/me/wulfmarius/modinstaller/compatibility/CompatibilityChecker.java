package me.wulfmarius.modinstaller.compatibility;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.ResponseEntity;

import me.wulfmarius.modinstaller.*;
import me.wulfmarius.modinstaller.rest.RestClient;
import me.wulfmarius.modinstaller.utils.JsonUtils;

public class CompatibilityChecker {

    private static final String TLD_VERSIONS_URL = "https://raw.githubusercontent.com/WulfMarius/Mod-Installer/master/tld-versions.json";

    private final Path basePath;
    private final RestClient restClient;
    private final CompatibilityState state;

    private final Map<ModDefinition, Compatibility> compatibilityCache = new ConcurrentHashMap<>();

    private String currentVersion = Version.VERSION_UNKNOWN;
    private Version parsedCurrentVersion;
    private CompatibilityVersion currentCompatibilityVersion;

    public CompatibilityChecker(Path basePath, RestClient restClient) {
        super();

        this.basePath = basePath;
        this.restClient = restClient;
        this.state = this.readState();
    }

    public Compatibility getCompatibility(ModDefinition modDefinition) {
        return this.compatibilityCache.computeIfAbsent(modDefinition, this::calculateCompatibility);
    }

    public String getCurrentVersion() {
        return this.currentVersion;
    }

    public CompatibilityState getState() {
        return this.state;
    }

    public void initialize() {
        this.readCurrentVersion();
        this.fetchTldVersions();
    }

    public void invalidate() {
        this.compatibilityCache.clear();
    }

    private Compatibility calculateCompatibility(ModDefinition modDefinition) {
        if (this.currentCompatibilityVersion == null) {
            return Compatibility.UNKNOWN;
        }

        CompatibilityVersion modCompatibilityVersion = Optional.ofNullable(modDefinition.getParsedCompatibleWith())
                .map(this.state.getCompatibilityVersions()::floor)
                .orElseGet(() -> this.state.getCompatibilityVersions().floor(modDefinition.getReleaseDate()));

        if (this.currentCompatibilityVersion.equals(modCompatibilityVersion)) {
            return Compatibility.OK;
        }

        return Compatibility.OLD;
    }

    private void fetchTldVersions() {
        try {
            ResponseEntity<String> response = this.restClient.fetch(TLD_VERSIONS_URL, this.state.getEtag());
            if (response.getStatusCode().is2xxSuccessful()) {
                this.state.setCompatibilityVersions(this.restClient.deserialize(response, CompatibilityVersions.class, null));
                this.state.setEtag(response.getHeaders().getETag());
                this.currentCompatibilityVersion = this.state.getCompatibilityVersions().floor(this.parsedCurrentVersion);
            }

            this.state.setChecked(new Date());
            this.writeState();
        } catch (AbortException e) {
            // ignore
        }
    }

    private Path getStatePath() {
        return this.basePath.resolve("compatibility-state.json");
    }

    private Optional<Path> getVersionFilePath() {
        Optional<Path> optional = Optional.of(this.basePath.resolveSibling("tld_Data/StreamingAssets/version.txt"))
                .filter(Files::exists);
        if (optional.isPresent()) {
            return optional;
        }

        optional = Optional.of(this.basePath.resolveSibling("tld.app/Contents/Resources/Data/StreamingAssets/version.txt"))
                .filter(Files::exists);
        return optional;
    }

    private void readCurrentVersion() {
        Path path = this.getVersionFilePath().orElseThrow(() -> new ModInstallerException("Could not find TLD version file."));

        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty()) {
                throw new ModInstallerException("TLD version file was empty.");
            }

            this.currentVersion = lines.get(0).split("\\s")[0];
            this.parsedCurrentVersion = Version.parse(this.currentVersion);
            this.currentCompatibilityVersion = this.state.getCompatibilityVersions().floor(this.parsedCurrentVersion);
            this.writeState();
        } catch (IllegalArgumentException | IOException e) {
            throw new ModInstallerException("Could not read TLD version.", e);
        }
    }

    private CompatibilityState readState() {
        try {
            Path path = this.getStatePath();
            if (Files.exists(path)) {
                return JsonUtils.deserialize(path, CompatibilityState.class);
            }
        } catch (IOException e) {
            // ignore
        }

        try (InputStream inputStream = this.getClass().getResourceAsStream("/default-compatibility-state.json")) {
            return JsonUtils.deserialize(inputStream, CompatibilityState.class);
        } catch (IOException e) {
            // ignore;
        }

        return new CompatibilityState();
    }

    private void writeState() {
        try {
            Path path = this.getStatePath();
            Files.createDirectories(path.getParent());
            JsonUtils.serialize(path, this.state);
        } catch (IOException e) {
            // ignore
        }
    }

    public enum Compatibility {
        UNKNOWN, OLD, OK;
    }
}
