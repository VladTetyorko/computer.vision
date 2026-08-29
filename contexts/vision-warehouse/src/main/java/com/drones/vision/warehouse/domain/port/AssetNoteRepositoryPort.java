package com.drones.vision.warehouse.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetNote;
import java.util.List;

/**
 * Driven port: persist and retrieve asset notes.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(AssetNote)} inserts a note — notes are append-only, never edited or
 *       deleted, so a handover note stays exactly as written.</li>
 *   <li>{@link #findByAsset(AssetId)} returns every note ever written against the asset.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — multiple control-plane operations may
 * write/read notes concurrently, and no caller assumes exclusive access.
 */
public interface AssetNoteRepositoryPort {

    /**
     * Appends a note.
     *
     * @param note the note to persist
     * @return the persisted note
     */
    AssetNote save(AssetNote note);

    /**
     * Lists every note ever written against an asset.
     *
     * @param assetId the asset id
     * @return an immutable snapshot of the asset's notes
     */
    List<AssetNote> findByAsset(AssetId assetId);
}
