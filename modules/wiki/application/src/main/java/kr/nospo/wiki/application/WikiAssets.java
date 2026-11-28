package kr.nospo.wiki.application;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import kr.nospo.member.application.ProfileImageCodec;
import kr.nospo.support.ApiException;
import kr.nospo.wiki.application.WikiDrafts.Edit;
import kr.nospo.wiki.application.WikiWorkflowStore.Asset;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static kr.nospo.wiki.application.WikiDrafts.*;

@Service
public class WikiAssets {
    private final WikiDrafts drafts;
    private final WikiWorkflowStore store;
    private final WikiPublic wiki;
    private final ProfileImageCodec codec;
    private final ObjectMapper json;

    public WikiAssets(WikiDrafts drafts, WikiWorkflowStore store, WikiPublic wiki,
                      ProfileImageCodec codec, ObjectMapper json) {
        this.drafts = drafts;
        this.store = store;
        this.wiki = wiki;
        this.codec = codec;
        this.json = json;
    }

    public Edit upload(UUID member, UUID draftId, String tag, byte[] input) {
        var draft = drafts.editable(member, draftId, tag);
        byte[] bytes = codec.sanitize(input);
        // The shared codec only emits PNG, whose IHDR stores width and height at byte 16.
        var dimensions = ByteBuffer.wrap(bytes, 16, 8);
        var asset = new Asset(UUID.randomUUID(), draft.bookId(), draftId, dimensions.getInt(), dimensions.getInt(), bytes);
        return drafts.edit(member, draftId, tag, editing -> {
            store.insertAsset(asset);
            var result = json.createObjectNode().put("assetId", asset.id().toString()).put("mediaType", "image/png")
                    .put("width", asset.width()).put("height", asset.height());
            snapshotArray(editing.content(), "assets").add(result);
            return result;
        });
    }

    public Edit remove(UUID member, UUID draftId, String tag, UUID asset) {
        return drafts.edit(member, draftId, tag, editing -> {
            var assets = snapshotArray(editing.content(), "assets");
            int index = indexOf(assets, "assetId", asset);
            for (String kind : List.of("characters", "organizations"))
                for (var entity : editing.content().path(kind))
                    for (var profile : entity.path("profiles"))
                        if (asset.toString().equals(profile.path("imageAssetId").asString()))
                            throw new ApiException(409, "ASSET_IN_USE");
            store.removeAsset(draftId, asset);
            assets.remove(index);
            return null;
        });
    }

    public Asset image(UUID member, UUID assetId, Integer position) {
        var asset = store.assetLocation(assetId).orElseThrow(WikiDrafts::missing);
        if (store.administrator(member)) return load(assetId);
        if (asset.draftId() != null) {
            var draft = drafts.find(member, asset.draftId());
            if (referenced(drafts.readableSnapshot(draft, draft.snapshot()), assetId)) return load(assetId);
            throw missing();
        }
        if (position == null) {
            for (var draft : store.draftsReferencing(member, asset.bookId(), assetId)) {
                try {
                    if (referenced(drafts.readableSnapshot(draft, draft.snapshot()), assetId)) return load(assetId);
                } catch (ApiException denied) {
                    if (denied.status() != 403) throw denied;
                }
            }
        }
        if (wiki.assetVisible(member, asset.bookId(), assetId, position)) return load(assetId);
        throw missing();
    }

    private Asset load(UUID assetId) { return store.asset(assetId).orElseThrow(WikiDrafts::missing); }

    private static boolean referenced(JsonNode content, UUID asset) {
        for (JsonNode item : content.path("assets"))
            if (asset.toString().equals(item.path("assetId").asString())) return true;
        return false;
    }
}
