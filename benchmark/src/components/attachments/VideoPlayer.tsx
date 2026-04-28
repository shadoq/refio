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
        // eslint-disable-next-line jsx-a11y/media-has-caption
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
