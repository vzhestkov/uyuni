'use strict';

const React = require("react");
const ReactDOM = require("react-dom");
const Network = require("../utils/network");

const Notifications = React.createClass({

  getInitialState: function () {
    return {
      unreadMessagesLength: 0,
      websocket: null
    }
  },

  onBeforeUnload: function(e) {
      this.setState({
          pageUnloading: true
      });
  },

  componentDidMount: function() {
    var port = window.location.port;
    var url = "wss://" +
      window.location.hostname +
      (port ? ":" + port : "") +
       "/rhn/websocket/notifications";
    var ws = new WebSocket(url);
    ws.onopen = () => {
        console.log('Websocket onOpen');
      this.setState({
      });
    };
    ws.onclose = (e) => {
        console.log('Websocket onClose');
      var errs = this.state.errors ? this.state.errors : [];
      if (!this.state.pageUnloading && !this.state.websocketErr) {
          errs.push(t("Websocket connection closed. Refresh the page to try again."));
      }
      this.setState({
          errors: errs,
          websocket: null
      });
    };
    ws.onerror = (e) => {
      console.log("Websocket error: " + e);
      this.setState({
         errors: [t("Error connecting to server. Refresh the page to try again.")],
         websocketErr: true
      });
    };
    ws.onmessage = (e) => {
        console.log('Websocket message=' + e.data);
        this.setState({unreadMessagesLength: e.data})
    };
    window.addEventListener("beforeunload", this.onBeforeUnload)

    this.setState({
        websocket: ws
    });
  },

  componentWillUnmount: function() {
    window.removeEventListener("beforeunload", this.onBeforeUnload)
  },

  render: function() {
    return (
        <a href="/rhn/manager/notification-messages">
          <i className="fa fa-bell"></i>
          <div id="notification-counter">
            {this.state.unreadMessagesLength}
          </div>
        </a>
    );
  }
});

// override the existing errorMessageByStatus from utils/network.js 
function errorMessageByStatus(status) {
  if (status == 401) {
    return [t("Session expired, please reload the page to receive notifications in real-time.")];
  }
  else if (status == 403) {
    return [t("Authorization error, please reload the page or try to logout/login again.")];
  }
  else if (status >= 500) {
    return [t("Server error, please check log files.")];
  }
  else {
    return [];
  }
}

ReactDOM.render(
  <Notifications />,
  document.getElementById('notifications')
);
