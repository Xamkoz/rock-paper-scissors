import { initializeApp } from "firebase/app";
import {
  getAuth,
  signInWithEmailAndPassword,
  type User,
} from "firebase/auth";
import { getFirestore } from "firebase/firestore";
import { getFunctions, connectFunctionsEmulator } from "firebase/functions";
import type { AgentConfig } from "../config.js";
import { FUNCTIONS_REGION } from "../config.js";

export interface FirebaseContext {
  config: AgentConfig;
  user: User;
  auth: ReturnType<typeof getAuth>;
  db: ReturnType<typeof getFirestore>;
  functions: ReturnType<typeof getFunctions>;
}

export async function initFirebase(config: AgentConfig): Promise<FirebaseContext> {
  const app = initializeApp({
    apiKey: config.apiKey,
    authDomain: config.authDomain,
    projectId: config.projectId,
    storageBucket: config.storageBucket,
  });
  const auth = getAuth(app);
  const credential = await signInWithEmailAndPassword(
    auth,
    config.botEmail,
    config.botPassword,
  );
  // Ensure Firestore requests carry a fresh ID token (avoids early PERMISSION_DENIED).
  await credential.user.getIdToken(true);
  const db = getFirestore(app);
  const functions = getFunctions(app, FUNCTIONS_REGION);

  if (process.env.FIREBASE_FUNCTIONS_EMULATOR_HOST) {
    const [host, portStr] = process.env.FIREBASE_FUNCTIONS_EMULATOR_HOST.split(":");
    connectFunctionsEmulator(functions, host, Number(portStr));
  }

  return {
    config,
    user: credential.user,
    auth,
    db,
    functions,
  };
}
