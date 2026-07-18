import { useState } from "react";
import { TodoList } from "./components/TodoList";
import { AddTodo } from "./components/AddTodo";

export interface Todo {
  id: number;
  text: string;
  done: boolean;
}

export function App() {
  // App is the top-level shell. It renders AddTodo and TodoList but does NOT
  // own the todo array itself - that state lives one level down, in TodoList.
  return (
    <main>
      <h1>Todos</h1>
      <TodoList />
    </main>
  );
}
