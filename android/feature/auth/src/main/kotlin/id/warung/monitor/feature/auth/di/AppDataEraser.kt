package id.warung.monitor.feature.auth.di

import id.warung.monitor.feature.auth.domain.LocalDataEraser

/**
 * Reset hook implemented in `app`, where the Room database and future Keystore
 * secrets live. Keeping the interface here lets `feature:auth` stay free of
 * database and Keystore dependencies.
 */
interface AppDataEraser : LocalDataEraser