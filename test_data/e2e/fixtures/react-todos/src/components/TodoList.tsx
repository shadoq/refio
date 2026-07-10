import { useState } from "react";
import type { Todo } from "../App";
import { TodoItem } from "./TodoItem";
import { AddTodo } from "./AddTodo";

export function TodoList() {
  // TodoList is the stateful owner of the todo collection. Any feature that
  // mutates all todos at once must have its handler defined here, where the
  // setTodos updater lives.
  const [todos, setTodos] = useState<Todo[]>([]);

  function addTodo(text: string) {
    setTodos((prev) => [...prev, { id: Date.now(), text, done: false }]);
  }

  function toggle(id: number) {
    setTodos((prev) =>
      prev.map((t) => (t.id === id ? { ...t, done: !t.done } : t))
    );
  }

  return (
    <section>
      <AddTodo onAdd={addTodo} />
      <ul>
        {todos.map((t) => (
          <TodoItem key={t.id} todo={t} onToggle={toggle} />
        ))}
      </ul>
    </section>
  );
}
