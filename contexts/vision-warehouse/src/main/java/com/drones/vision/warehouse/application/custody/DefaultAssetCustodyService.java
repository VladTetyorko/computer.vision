package com.drones.vision.warehouse.application.custody;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.InventoryState;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.MaintenanceRepositoryPort;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * The one implementation of {@link AssetCustodyService}.
 *
 * <p>Reads and writes {@link Asset} through {@link AssetRepositoryPort} directly, exactly like
 * {@code DefaultAssetService} — a peer service over the same aggregate, not a wrapper around
 * {@code AssetService} (which has no raw save). {@code ground}/{@code release} open/close {@link
 * MaintenanceRecord}s through {@link MaintenanceRepositoryPort} directly rather than through
 * {@code MaintenanceService}: a maintenance record's own construction/closing logic is a single
 * compact-constructor's worth of validation with no rule this class would otherwise duplicate, so
 * routing through a peer service here would only add an authorisation/audit round-trip with no
 * corresponding reduction in duplication (contrast {@code DefaultAssetService}'s genuine reuse of
 * {@code DeviceService}, where device lifecycle rules really would drift if reimplemented here).
 */
public final class DefaultAssetCustodyService implements AssetCustodyService {

    private static final String ATTR_ASSET_ID = "assetId";
    private static final String ATTR_ACTION = "action";
    private static final String ATTR_RESULT = "result";
    private static final String DENIED_OUT_OF_SCOPE = "DENIED:out of scope";

    private final AssetRepositoryPort assetRepository;
    private final MaintenanceRepositoryPort maintenanceRepository;
    private final AuditTrailPort auditTrail;

    public DefaultAssetCustodyService(AssetRepositoryPort assetRepository,
                                       MaintenanceRepositoryPort maintenanceRepository, AuditTrailPort auditTrail) {
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
        this.maintenanceRepository =
                Objects.requireNonNull(maintenanceRepository, "maintenanceRepository must not be null");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail must not be null");
    }

    @Override
    public Asset issue(AssetId id, UserId custodianId, String location, UserId actor, Authority scope) {
        Objects.requireNonNull(custodianId, "custodianId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Asset asset = requireManageable(id, actor, scope, "ISSUE");
        if (asset.inventoryState() != InventoryState.IN_STOCK || asset.custody().custodianId() != null) {
            throw new IllegalStateException("Asset " + asset.displayName()
                    + " must be in stock and unassigned to be issued; is " + asset.inventoryState()
                    + " with custodian " + asset.custody().custodianId());
        }

        Instant now = Instant.now();
        Asset saved = assetRepository.save(
                asset.withInventory(new Custody(custodianId, location, now), InventoryState.IN_STOCK, now));
        audit(actor, id, "ISSUE", "Issued asset " + saved.displayName() + " to " + custodianId.value());
        return saved;
    }

    @Override
    public Asset returnToStock(AssetId id, UserId actor, Authority scope) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Asset asset = requireManageable(id, actor, scope, "RETURN");
        if (asset.inventoryState() != InventoryState.IN_STOCK || asset.custody().custodianId() == null) {
            throw new IllegalStateException("Asset " + asset.displayName() + " is not issued to anyone");
        }

        Instant now = Instant.now();
        Asset saved = assetRepository.save(asset.withInventory(Custody.NONE, InventoryState.IN_STOCK, now));
        audit(actor, id, "RETURN", "Returned asset " + saved.displayName() + " to stock");
        return saved;
    }

    @Override
    public Asset ground(AssetId id, MaintenanceKind kind, String summary, UserId actor, Authority scope) {
        Objects.requireNonNull(kind, "kind must not be null");
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Asset asset = requireManageable(id, actor, scope, "GROUND");
        if (asset.inventoryState() == InventoryState.RETIRED) {
            throw new IllegalStateException("Asset " + asset.displayName() + " is retired; it cannot be grounded");
        }

        Instant now = Instant.now();
        maintenanceRepository.save(new MaintenanceRecord(MaintenanceId.random(), id, kind, now, null, actor,
                summary, null));
        Asset saved = assetRepository.save(asset.withInventory(asset.custody(), InventoryState.MAINTENANCE, now));
        audit(actor, id, "GROUND", "Grounded asset " + saved.displayName() + " (" + kind + "): " + summary);
        return saved;
    }

    @Override
    public Asset release(AssetId id, UserId actor, Authority scope) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Asset asset = requireManageable(id, actor, scope, "RELEASE");
        if (asset.inventoryState() != InventoryState.MAINTENANCE) {
            throw new IllegalStateException(
                    "Asset " + asset.displayName() + " is not in maintenance; is " + asset.inventoryState());
        }

        Instant now = Instant.now();
        int closed = 0;
        for (MaintenanceRecord record : maintenanceRepository.findOpenByAsset(id)) {
            if (record.kind().blocksFlight()) {
                maintenanceRepository.save(record.close(now));
                closed++;
            }
        }
        Asset saved = assetRepository.save(asset.withInventory(Custody.NONE, InventoryState.IN_STOCK, now));
        audit(actor, id, "RELEASE",
                "Released asset " + saved.displayName() + " from maintenance (" + closed + " blocker(s) closed)");
        return saved;
    }

    @Override
    public Asset retire(AssetId id, UserId actor, Authority scope) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Asset asset = requireManageable(id, actor, scope, "RETIRE");
        if (asset.inventoryState() == InventoryState.RETIRED) {
            return asset; // idempotent, mirrors DefaultAssetService#setState
        }
        if (asset.custody().custodianId() != null) {
            throw new IllegalStateException(
                    "Asset " + asset.displayName() + " is issued to someone; return it to stock before retiring");
        }

        Instant now = Instant.now();
        Asset saved = assetRepository.save(asset.withInventory(asset.custody(), InventoryState.RETIRED, now));
        audit(actor, id, "RETIRE", "Retired asset " + saved.displayName());
        return saved;
    }

    private Asset requireManageable(AssetId id, UserId actor, Authority scope, String action) {
        Objects.requireNonNull(id, "id must not be null");
        Asset asset = assetRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown asset: " + id.value()));
        if (!scope.mayManageFleet(asset.ownership())) {
            audit(actor, id, action, DENIED_OUT_OF_SCOPE);
            throw new AccessDeniedException("Asset " + id.value() + " is outside your management scope; you may not "
                    + action.toLowerCase(Locale.ROOT) + " it");
        }
        return asset;
    }

    private void audit(UserId actor, AssetId assetId, String action, String summary) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(ATTR_ASSET_ID, assetId.value().toString());
        attributes.put(ATTR_ACTION, action);
        attributes.put(ATTR_RESULT, summary);
        auditTrail.record(AuditEntry.of(actor, AuditAction.UPDATED, AuditTargetType.ASSET,
                assetId.value().toString(), summary, attributes));
    }
}
