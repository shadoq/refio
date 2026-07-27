interface VideoPlayerProps {
  src: string;
  type: "video" | "video-embed";
  caption?: string;
}

export function VideoPlayer({ src, type, caption }: VideoPlayerProps) {
  return (
    <div>
      {type === "video-embed" ? (
        <iframe
          src={src}
          style={{ width: "100%", height: 400, border: "none", borderRadius: 4 }}
          allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
          allowFullScreen
          title={caption ?? "Video"}
        />
      ) : (
        // No <track>: these are screen recordings of benchmark runs, captured without audio,
        // so there is nothing to caption. (The jsx-a11y plugin this used to silence is not
        // installed, and ESLint errors on a disable comment for a rule it cannot resolve.)
        <video
          controls
          style={{ width: "100%", maxHeight: 400, borderRadius: 4 }}
          src={`/data/${src}`}
        />
      )}
      {caption && (
        <div style={{ marginTop: 8, color: "#666", fontSize: 12 }}>{caption}</div>
      )}
    </div>
  );
}
