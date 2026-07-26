import { useState } from "react";
import { Button, Image, Space, Tooltip, Typography } from "antd";
import { HtmlSandbox } from "./HtmlSandbox";
import { dataUrl, ideaOpenHref, repoPath } from "@/lib/adminArtifacts";

const { Text } = Typography;

interface ArtifactPreviewProps {
  htmlSrc: string | null;
  screenshots: string[];
  height?: number;
}

// Review preview of a produced artifact. The live HTML render is opt-in: a demoscene
// page runs a requestAnimationFrame loop, and mounting one live sandbox per queue card
// at once freezes the browser. So by default it shows the captured screenshot(s) plus
// controls to copy the file path or open it in IntelliJ; the reviewer starts the live
// sandbox only to interact with it, and hiding it unmounts the iframe (stops the loop).
export function ArtifactPreview({ htmlSrc, screenshots, height = 360 }: ArtifactPreviewProps) {
  const [live, setLive] = useState(false);
  const dataRoot = import.meta.env.VITE_DATA_ROOT;

  return (
    <Space direction="vertical" size="small" style={{ width: "100%" }}>
      {screenshots.length > 0 && (
        <Image.PreviewGroup>
          <Space wrap>
            {screenshots.map((src) => (
              <Image
                key={src}
                src={dataUrl(src)}
                alt="generated screenshot"
                width={220}
                style={{ border: "1px solid rgba(0,0,0,0.15)", borderRadius: 4 }}
              />
            ))}
          </Space>
        </Image.PreviewGroup>
      )}

      {htmlSrc && (
        <>
          <Space wrap size="small">
            <Button
              size="small"
              type={live ? "default" : "primary"}
              onClick={() => setLive((v) => !v)}
            >
              {live ? "Hide live preview" : "Run live preview"}
            </Button>
            <Tooltip title={repoPath(htmlSrc)}>
              <a href={ideaOpenHref(htmlSrc, dataRoot)}>Open in IntelliJ</a>
            </Tooltip>
            <Text copyable={{ text: repoPath(htmlSrc) }} style={{ fontSize: 12 }}>
              Copy file path
            </Text>
            <Text
              copyable={{ text: `${window.location.origin}${dataUrl(htmlSrc)}` }}
              style={{ fontSize: 12 }}
            >
              Copy URL
            </Text>
          </Space>
          {live ? (
            <HtmlSandbox src={htmlSrc} height={height} caption="Produced artifact (live)" />
          ) : (
            screenshots.length === 0 && (
              <Text type="secondary" style={{ fontSize: 12 }}>
                Live preview is off (it starts an animation). No screenshot was captured for this run.
              </Text>
            )
          )}
        </>
      )}
    </Space>
  );
}
