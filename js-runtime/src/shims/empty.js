// Empty shim for node-builtins that the bundler accidentally pulls in.
// Anything imported from these modules will be `undefined`, which is fine
// for the codepaths youtubei.js exercises in browser mode.
export default {};
