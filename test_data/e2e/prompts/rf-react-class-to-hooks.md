`app.jsx` holds the ops dashboard's status board, written years ago in the
class-component style the codebase has since moved away from: constructor
state, explicitly bound methods, and the polling timer juggled across
`componentDidMount` / `componentDidUpdate` / `componentWillUnmount`.

Your task:

1. Rewrite `app.jsx` in place as a function component built on hooks:
   `useState` for the state fields, `useEffect` for the feed fetch and for
   the polling timer.
2. Get the effect bookkeeping right: the timer must be cleared on unmount and
   whenever an effect re-runs, polling must restart when `refreshInterval`
   changes, and the dependency arrays must be complete enough that no closure
   goes stale.
3. Keep the observable behaviour: the same initial fetch, polling only while
   auto-refresh is on, the same filter buttons and toggle, the same rendered
   markup and class names, and the same props with the same defaults.
4. No class component may remain - no `extends React.Component`, no
   constructor, no lifecycle methods.

Do not modify the README: the rewrite belongs in `app.jsx`.
