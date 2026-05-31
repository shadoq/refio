import { DownloadOutlined, FileOutlined, FileZipOutlined } from "@ant-design/icons";
import { Button, Space, Typography } from "antd";

interface FileDownloadProps {
  src: string;
  type: "archive" | "file";
  caption?: string;
}

function filenameFromSrc(src: string): string {
  const clean = src.split("?")[0].split("#")[0];
  const parts = clean.split("/").filter(Boolean);
  return parts.length > 0 ? parts[parts.length - 1] : src;
}

export function FileDownload({ src, type, caption }: FileDownloadProps) {
  const url = src.startsWith("http") ? src : `/data/${src}`;
  const filename = filenameFromSrc(src);
  const Icon = type === "archive" ? FileZipOutlined : FileOutlined;

  return (
    <Space direction="vertical" size={8} style={{ width: "100%" }}>
      <Space>
        <Icon />
        <Typography.Text>{caption ?? filename}</Typography.Text>
      </Space>
      <Button icon={<DownloadOutlined />} href={url} download={filename}>
        Download {type === "archive" ? "archive" : "file"}
      </Button>
    </Space>
  );
}
