`src/client.ts` is the report script for the orders service, and `src/models.ts` holds its
label helpers. Nothing is typed: the API methods are annotated `any`, the resource shapes
exist only implicitly inside the helpers, and the compiler cannot catch a renamed field.
The script is about to be reused as an imported module, so it needs real types.

Your task:

1. Introduce interfaces for the resource shapes - `User`, `Order` and `OrderLine` - in
   `src/models.ts`, and type the label helpers and the currency table against them.
2. Rework `src/client.ts` around a generic `request<T>` so every method returns a concrete
   `Promise<...>`: `Promise<User>` and `Promise<Order>` for single resources, and a
   `Promise<Paginated<T>>` for listings (declare that pagination interface in `client.ts`).
3. Type the error path: the non-2xx branch should unwrap a typed error body and include its
   message in the thrown error.
4. Remove every `any` from both files and export the client class and the shapes so other
   modules can import them; the result must be strict-mode clean.

Do not modify the README: the changes belong in `src/client.ts` and `src/models.ts`.
