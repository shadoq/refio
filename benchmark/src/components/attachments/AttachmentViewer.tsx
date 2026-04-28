import type { Attachment } from "@/schema/results";
import { ImageViewer } from "./ImageViewer";
import { HtmlSandbox } from "./HtmlSandbox";
import { VideoPlayer } from "./VideoPlayer";
import { FileDownload } from "./FileDownload";

interface AttachmentViewerProps {
  attachment: Attachment;
}

export function AttachmentViewer({ attachment }: AttachmentViewerProps) {
  switch (attachment.type) {
    case "image":
      return <ImageViewer src={attachment.src} caption={attachment.caption} />;
    case "html":
      return <HtmlSandbox src={attachment.src} caption={attachment.caption} />;
    case "video":
    case "video-embed":
      return (
        <VideoPlayer
          src={attachment.src}
          type={attachment.type}
          caption={attachment.caption}
        />
      );
    case "archive":
    case "file":
      return (
        <FileDownload
          src={attachment.src}
          type={attachment.type}
          caption={attachment.caption}
        />
      );
  }
}
