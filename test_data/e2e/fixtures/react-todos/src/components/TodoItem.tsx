import type { Todo } from "../App";

interface TodoItemProps {
  todo: Todo;
  onToggle: (id: number) => void;
}

export function TodoItem({ todo, onToggle }: TodoItemProps) {
  // Presentational leaf. Holds no state; receives a todo and a toggle callback.
  return (
    <li onClick={() => onToggle(todo.id)}>
      {todo.done ? "[x] " : "[ ] "}
      {todo.text}
    </li>
  );
}
