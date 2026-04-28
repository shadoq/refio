import { Image } from "antd";

interface ImageViewerProps {
  src: string;
  caption?: string;
}

export function ImageViewer({ src, caption }: ImageViewerProps) {
  const url = src.startsWith("http") ? src : `/data/${src}`;
  return (
    <div style={{ textAlign: "center" }}>
      <Image
        src={url}
        alt={caption ?? "Attachment"}
        style={{ maxWidth: "100%", maxHeight: 500 }}
      />
      {caption && (
        <div style={{ marginTop: 8, color: "#666", fontSize: 12 }}>{caption}</div>
      )}
    </div>
  );
}
