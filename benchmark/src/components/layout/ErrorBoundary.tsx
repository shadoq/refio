import { Component, type ReactNode, type ErrorInfo } from "react";
import { useT } from "@/i18n/LanguageProvider";
import { Result, Button } from "antd";

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("ErrorBoundary caught:", error, info);
  }

  reset = () => {
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      return (
        <ErrorView message={this.state.error?.message ?? null} onRetry={this.reset} />
      );
    }
    return this.props.children;
  }
}

// Hooks are not available in the class above, so the translated text lives here.
function ErrorView({ message, onRetry }: { message: string | null; onRetry: () => void }) {
  const t = useT();
  return (
    <Result
      status="error"
      title={t("layout.errorTitle")}
      subTitle={message ?? t("layout.errorUnexpected")}
      extra={
        <Button type="primary" onClick={onRetry}>
          {t("layout.errorRetry")}
        </Button>
      }
    />
  );
}
