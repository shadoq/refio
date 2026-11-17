/*
 * Asset downloader service (legacy callback implementation).
 *
 * downloadPipeline(url, opts, callback) runs the five-step batch download:
 *
 *     getUser -> getPermissions -> fetchAsset -> transform -> save
 *
 * The in-memory tables stand in for the user service, the permission service
 * and the asset CDN used in production; the setTimeout delays keep the
 * asynchronous shape of the real network round trips so the pipeline stays
 * honest to test. Every step follows the error-first convention.
 */
"use strict";

var USERS = {
  "u-1": { id: "u-1", name: "ada", plan: "pro" },
  "u-2": { id: "u-2", name: "brin", plan: "free" },
  "u-3": { id: "u-3", name: "curie", plan: "enterprise" }
};

var PERMISSIONS = {
  "u-1": ["asset:read", "asset:transform"],
  "u-2": ["billing:read"],
  "u-3": ["asset:read", "asset:transform", "asset:admin"]
};

var ASSETS = {
  "report-q3": { id: "report-q3", bytes: 2048, encoding: "gzip" },
  "logo-blob": { id: "logo-blob", bytes: 512, encoding: "identity" },
  "video-teaser": { id: "video-teaser", bytes: 65536, encoding: "brotli" },
  "dataset-bulk": { id: "dataset-bulk", bytes: 900000, encoding: "gzip" }
};

var SUPPORTED_ENCODINGS = ["identity", "gzip"];
var SAVE_QUOTA_BYTES = 262144;
var ROUND_TRIP_MS = 10;

var SAVED = {}; // written records, keyed by file name

// Parse "dl://<userId>/<assetId>" asset urls.
function parseAssetUrl(url) {
  var match = /^dl:\/\/([a-z0-9-]+)\/([a-z0-9-]+)$/i.exec(String(url));
  if (!match) {
    return null;
  }
  return { userId: match[1], assetId: match[2] };
}

function getUser(userId, callback) {
  setTimeout(function () {
    var user = USERS[userId];
    if (!user) {
      return callback(new Error("unknown user: " + userId));
    }
    callback(null, user);
  }, ROUND_TRIP_MS);
}

function getPermissions(user, callback) {
  setTimeout(function () {
    var perms = PERMISSIONS[user.id];
    if (!perms) {
      return callback(new Error("no permission record for " + user.id));
    }
    callback(null, perms);
  }, ROUND_TRIP_MS);
}

function fetchAsset(assetId, callback) {
  setTimeout(function () {
    var asset = ASSETS[assetId];
    if (!asset) {
      return callback(new Error("asset not found: " + assetId));
    }
    callback(null, asset);
  }, ROUND_TRIP_MS);
}

function transform(asset, opts, callback) {
  setTimeout(function () {
    if (SUPPORTED_ENCODINGS.indexOf(asset.encoding) === -1) {
      return callback(new Error("transform failed: unsupported encoding " + asset.encoding));
    }
    var compact = !!(opts && opts.compact);
    var bytes = compact ? Math.floor(asset.bytes / 2) : asset.bytes;
    callback(null, {
      assetId: asset.id,
      encoding: asset.encoding,
      bytes: bytes,
      compact: compact
    });
  }, ROUND_TRIP_MS);
}

function save(record, opts, callback) {
  setTimeout(function () {
    if (record.bytes > SAVE_QUOTA_BYTES) {
      return callback(new Error("save failed: quota exceeded (" + record.bytes + " > " + SAVE_QUOTA_BYTES + ")"));
    }
    var prefix = opts && opts.prefix ? opts.prefix + "-" : "";
    var name = prefix + record.assetId + ".bin";
    SAVED[name] = record;
    callback(null, { savedAs: name, bytes: record.bytes });
  }, ROUND_TRIP_MS);
}

function downloadPipeline(url, opts, callback) {
  var parsed = parseAssetUrl(url);
  if (!parsed) {
    return callback(new Error("malformed url: " + url));
  }
  getUser(parsed.userId, function (err, user) {
    if (err) return callback(err);
    getPermissions(user, function (err, permissions) {
      if (err) return callback(err);
      if (permissions.indexOf("asset:read") === -1) {
        return callback(new Error("permission denied: " + user.id + " lacks asset:read"));
      }
      fetchAsset(parsed.assetId, function (err, asset) {
        if (err) return callback(err);
        transform(asset, opts, function (err, transformed) {
          if (err) return callback(err);
          save(transformed, opts, function (err, saved) {
            if (err) return callback(err);
            callback(null, saved);
          });
        });
      });
    });
  });
}

module.exports = {
  downloadPipeline: downloadPipeline,
  SAVED: SAVED
};
