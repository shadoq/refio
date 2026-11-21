/**
 * Service status board - legacy React class component.
 *
 * Renders the ops dashboard incident list: polls the incident feed on an
 * interval, keeps a severity filter plus an auto-refresh toggle, and
 * restarts polling when the refreshInterval prop changes. Written in the
 * pre-hooks style the rest of the dashboard still uses: constructor state,
 * explicitly bound methods, lifecycle bookkeeping.
 */
import React from "react";

const SEVERITIES = ["all", "critical", "warning", "info"];

class StatusBoard extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      incidents: [],
      filter: "all",
      autoRefresh: props.autoRefresh !== false,
      lastFetchedAt: null,
      fetchError: null,
      loading: true
    };
    this.handleFilterChange = this.handleFilterChange.bind(this);
    this.toggleAutoRefresh = this.toggleAutoRefresh.bind(this);
    this.fetchIncidents = this.fetchIncidents.bind(this);
    this.startPolling = this.startPolling.bind(this);
    this.stopPolling = this.stopPolling.bind(this);
  }

  componentDidMount() {
    this.fetchIncidents();
    this.startPolling();
  }

  componentDidUpdate(prevProps) {
    if (prevProps.refreshInterval !== this.props.refreshInterval) {
      // interval changed: drop the old timer and start a new one
      this.startPolling();
    }
  }

  componentWillUnmount() {
    this.stopPolling();
  }

  startPolling() {
    this.stopPolling();
    if (!this.state.autoRefresh) {
      return;
    }
    this.pollTimer = setInterval(
      this.fetchIncidents,
      this.props.refreshInterval || 5000
    );
  }

  stopPolling() {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  fetchIncidents() {
    fetch(this.props.feedUrl)
      .then(function (response) {
        if (!response.ok) {
          throw new Error("feed request failed: " + response.status);
        }
        return response.json();
      })
      .then(
        function (data) {
          this.setState({
            incidents: data.incidents || [],
            lastFetchedAt: new Date().toISOString(),
            fetchError: null,
            loading: false
          });
        }.bind(this)
      )
      .catch(
        function (err) {
          this.setState({ fetchError: err.message, loading: false });
        }.bind(this)
      );
  }

  handleFilterChange(filter) {
    this.setState({ filter: filter });
  }

  toggleAutoRefresh() {
    this.setState(
      function (state) {
        return { autoRefresh: !state.autoRefresh };
      },
      this.startPolling
    );
  }

  render() {
    const incidents = this.state.incidents;
    const filter = this.state.filter;
    const visible =
      filter === "all"
        ? incidents
        : incidents.filter(function (incident) {
            return incident.severity === filter;
          });
    const criticalCount = incidents.filter(function (incident) {
      return incident.severity === "critical";
    }).length;

    return (
      <section className="status-board">
        <header>
          <h2>Service status</h2>
          <span className="meta">
            {criticalCount} critical - updated {this.state.lastFetchedAt || "never"}
          </span>
          <button type="button" onClick={this.toggleAutoRefresh}>
            auto-refresh: {this.state.autoRefresh ? "on" : "off"}
          </button>
        </header>
        {this.state.fetchError ? (
          <p className="error">feed error: {this.state.fetchError}</p>
        ) : null}
        {this.state.loading ? <p className="loading">loading...</p> : null}
        <nav>
          {SEVERITIES.map(
            function (severity) {
              return (
                <button
                  type="button"
                  key={severity}
                  className={severity === filter ? "active" : ""}
                  onClick={function () {
                    this.handleFilterChange(severity);
                  }.bind(this)}
                >
                  {severity}
                </button>
              );
            }.bind(this)
          )}
        </nav>
        <ul>
          {visible.map(function (incident) {
            return (
              <li key={incident.id} className={"incident " + incident.severity}>
                <strong>{incident.title}</strong>
                <span>{incident.component}</span>
              </li>
            );
          })}
        </ul>
      </section>
    );
  }
}

StatusBoard.defaultProps = {
  feedUrl: "/api/incidents",
  refreshInterval: 5000,
  autoRefresh: true
};

export default StatusBoard;
